package diskiyord.ws;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.neovisionaries.ws.client.*;
import diskiyord.api.DiskiyordApi;
import diskiyord.logger.WebsocketLogger;
import diskiyord.util.JsonPacket;
import diskiyord.util.ObjectContainer;
import diskiyord.util.gateway.GatewayEvent;
import diskiyord.util.gateway.GatewayOpcode;
import diskiyord.util.zip.ZlibDecompressor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * An extension of a Websocket adapter for Discord API connections.
 *
 * Clients are limited to 1000 IDENTIFY calls to the websocket in a 24-hour period. This limit is global and across all
 * shards, but does not include RESUME calls. Upon hitting this limit, all active sessions for the bot will be
 * terminated, the bot's token will be reset, and the owner will receive an email notification. It's up to the owner to
 * update their application with the new token.
 *
 * @author dk
 */
public class DiscordWebsocketAdapter extends WebSocketAdapter {

	private String wss;
	private Gson gson;
	private ScheduledExecutorService threadpool;
//	private AtomicReference<WebSocketFrame> nextHeartbeatFrame = new AtomicReference<>();

	// When incrementing, need AtomicInteger as volatile will not guarantee atomicity with increments(read+write)
	private AtomicInteger reconnectAttempt = new AtomicInteger();
	private volatile int rateLimitDelay = 5432;
	private volatile int lastSeq = -1;
	private volatile boolean heartbeatAckReceived;
	private volatile boolean reconnect;

	private final DiskiyordApi api;
	private final String token;
	private final AtomicReference<String> sessionId = new AtomicReference<>();
	private final AtomicReference<WebSocket> websocket = new AtomicReference<>();

	private static final int GATEWAY_VERSION = 6;
	private static final String ENCODING = "json";
	private static final double VERSION = 0.1;
	private static final String GET_URL = "https://www.discordapp.com/api/gateway";
	private static final Logger LOGGER = LogManager.getLogger();

	public DiscordWebsocketAdapter(DiskiyordApi api) {
		this.api = api;
		this.wss = "";
		this.gson = new Gson();
		this.threadpool = Executors.newScheduledThreadPool(1);
		this.heartbeatAckReceived = false;
		this.reconnect = true;
		this.token = this.api.getToken();

		getWss();
		connect();
	}

	/**
	 * Gets the secure websocket from the first connection to the Discord api gateway
	 * GET /api/gateway
	 */
	private void getWss() {
		try {
			URL url = new URL(GET_URL);
			HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();

			connection.setDoInput(true);
			connection.setRequestMethod("GET");
			connection.setRequestProperty("User-Agent", String.format("diskiyord (v%s)", VERSION));
			connection.setRequestProperty("Content-Type", "application/json");

			LOGGER.info("Status Code: {} {}", connection.getResponseCode(), connection.getResponseMessage());
			BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
			String line = reader.readLine();
			this.wss = parseJson(line, "url");
			LOGGER.debug(this.wss);
		} catch (MalformedURLException mue) {
			LOGGER.fatal("URL is malformed, {},\n{}", mue.getMessage(), mue.getStackTrace());
		} catch (IOException ioe) {
			LOGGER.fatal("Error has occurred when attempting connection, {},\n{}", ioe.getMessage(),
					ioe.getStackTrace());
		}
	}

	/**
	 * Tries to retrieve from the input json string, the value of a specific key.
	 * @param json - json string
	 * @param key - key to get value from
	 * @return value
	 */
	private String parseJson(String json, String key) {
		String value = "";
		JsonObject obj = gson.fromJson(json, JsonObject.class);
		if(obj.has(key)) {
			value = obj.get(key).getAsString();
		}
		return value;
	}

    /**
     * Connect to the cached websocket
     */
	private void connect() {
		String wssUri = String.format("%1$s/?v=%2$s&encoding=%3$s", this.wss, GATEWAY_VERSION, ENCODING);
		// Create a WebSocketFactory instance.
		WebSocketFactory factory = new WebSocketFactory();
		try {
			factory.setSSLContext(SSLContext.getDefault());
		} catch (NoSuchAlgorithmException nsae) {
			LOGGER.warn("An error occurred while setting ssl context", nsae);
		}
		try {
			URI uri = new URI(wssUri);
			LOGGER.debug("URI: {}", uri);
			WebSocket ws = factory.createSocket(uri);
			this.websocket.set(ws);
			// Register listeners to receive WebSocket events.
			ws.addListener(this);
			ws.addListener(new WebsocketLogger());
			//TODO: change rate limit from a hard 5.432 seconds
			rateLimit();
			ws.connect();
		} catch(Throwable e) {
			LOGGER.warn("Error has occured when connecting to the websocket, {},\n{}", e.getMessage(), e.getStackTrace());
			if(reconnect) {
				this.reconnectAttempt.incrementAndGet();
				this.threadpool.scheduleAtFixedRate(this::connect,
						0, calculateReconnectDelay(this.reconnectAttempt), TimeUnit.SECONDS);
			}
		}
	}

	/**
	 * Need to wait at least 5 seconds in between IDENTIFY calls
	 */
	private void rateLimit() {
		// delay of 5432 milliseconds
		try {
			Thread.sleep(this.rateLimitDelay);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		LOGGER.debug(String.format("Waited for %f seconds", (this.rateLimitDelay/1000.0)));
	}

	/**
	 * Calculates the reconnect delay time when bot gets disconnected. The delay time increases when more
	 * attempts are made to reconnect.
	 * @param reconnectAttempt;
	 * @return delay
	 */
	private int calculateReconnectDelay(AtomicInteger reconnectAttempt) {
		int x = reconnectAttempt.get();
		int delay = (int) Math.floor(2*x - (4*x/(Math.log(x) + 2)));
		LOGGER.debug("delay={}", delay);
		return delay;
	}

	@Override
	public void onBinaryMessage(WebSocket websocket, byte[] binary) throws  Exception {
		ZlibDecompressor decompressor = new ZlibDecompressor();
		String output = decompressor.decompress(binary);
		LOGGER.debug("onBinaryMessage: decompressed={}", output);

		JsonPacket messagePacket = new JsonPacket(output);
		String event = messagePacket.get("t").asString();

		ObjectContainer<GatewayEvent> gatewayEvent = GatewayEvent.fromEvent(event);
		/*
		 * Must have gotten an illegal opcode
		 */
		if(!gatewayEvent.objectIsPresent()) {
			LOGGER.error("Received an unknown payload. \"op\"={} does not exist.", event);
			return;
		}

		switch(gatewayEvent.getObject()) {
			case READY:
				this.sessionId.set(messagePacket.get("d").asPacket().get("session_id").asString());
				break;
			case GUILD_CREATE:
				break;
		}
	}

	@Override
	public void onDisconnected(WebSocket websocket, WebSocketFrame serverCloseFrame, WebSocketFrame clientCloseFrame,
							   boolean closedByServer) throws Exception {
		// If can reconnect, try to reconnect after a delay based on the amount of reconnect attempts
		if(reconnect) {
			LOGGER.debug("Attempting to reconnect...");
			this.reconnectAttempt.incrementAndGet();
//			this.threadpool.scheduleAtFixedRate(this::connect,
//					0, calculateReconnectDelay(this.reconnectAttempt), TimeUnit.SECONDS);
			connect();
		}
	}

	@Override
	public void onError(WebSocket websocket, WebSocketException cause) throws Exception {
		LOGGER.debug("onError...");
	}

	@Override
	public void onTextMessage(WebSocket websocket, String message) throws Exception {
		LOGGER.info("onTextMessage: message={}", message);
		JsonPacket messagePacket = new JsonPacket(message);
		int op = messagePacket.get("op").asInt();
		ObjectContainer<GatewayOpcode> gatewayOpcode = GatewayOpcode.fromOpcode(op);
		/*
		 * Must have gotten an illegal opcode
		 */
		if(!gatewayOpcode.objectIsPresent()) {
			LOGGER.error("Received an unknown payload. \"op\"={} does not exist.", op);
			return;
		}
		/*
		 * If a client does not receive a heartbeat ack between its attempts at sending heartbeats, it should
		 * immediately terminate the connection with a non-1000 close code, reconnect, and attempt to resume.
		 */
		switch(gatewayOpcode.getObject()) {
			case DISPATCH:
				LOGGER.debug("Received dispatch");
				/*
				 * Format:
				 * 		{
							"t": "READY",
							"s": 1,
							"op": 0,
							"d": {
								"v": 6,
								"user_settings": {},
								"user": {
									"verified": true,
									"username": "Kiyobot",
									"mfa_enabled": true,
									"id": "<id>",
									"email": null,
									"discriminator": "<#>",
									"bot": true,
									"avatar": "<avatar>"
								},
								"session_id": "<id>",
								"relationships": [],
								"private_channels": [],
								"presences": [],
								"guilds": [{
									"unavailable": true,
									"id": "<id>"
								}],
								"_trace": ["gateway-prd-main-fp7m", "discord-sessions-prd-1-11"]
							}
						}
				 */
				//TODO: GUILD_CREATE is received here
				lastSeq = messagePacket.get("s").asInt();
				String type = messagePacket.get("t").asString();
				if(type.equals(GatewayEvent.READY.getEvent())) {
					reconnectAttempt.set(0);
					sessionId.set(messagePacket.get("d").asPacket().get("session_id").asString());
				}
				break;
			case HEARTBEAT:
				LOGGER.debug("Received heartbeat");
				sendHeartbeat(websocket);
				break;
			case IDENTIFY:
				LOGGER.debug("Received identify");
				break;
			case STATUS_UPDATE:
				LOGGER.debug("Received status update");
				break;
			case VOICE_STATE_UPDATE:
				LOGGER.debug("Received voice status update");
				break;
			case VOICE_SERVER_PING:
				LOGGER.debug("Received voice server ping");
				break;
			case RESUME:
				LOGGER.debug("Received resume");
				break;
			case RECONNECT:
				LOGGER.debug("Received reconnect");
				break;
			case REQUEST_GUILD_MEMBERS:
				LOGGER.debug("Received request guild members");
				break;
			case INVALID_SESSION:
				LOGGER.debug("Received invalid session");
				break;
			case HELLO:
				int heartbeatInterval = messagePacket.get("d").asPacket().get("heartbeat_interval").asInt();
				LOGGER.info("heartbeat_interval: {}", heartbeatInterval);
				startHeartbeat(websocket, heartbeatInterval);
				sendIdentify(websocket);
				break;
			case HEARTBEAT_ACK:
				LOGGER.info("Received heartbeat ack");
				this.heartbeatAckReceived = true;
				break;
			default:
				LOGGER.error("Received an unknown payload. {\"op\": {}}.", op);
				break;
		}
	}

	/**
	 * Gets an atomic reference to this websocket
	 * @return this.websocket
	 */
	public AtomicReference<WebSocket> getWebsocket() {
		return websocket;
	}

	/**
	 * Schedules heartbeats to send back to the gateway
	 * @param websocket - the websocket
	 * @param heartbeatInterval - delay between heartbeats
	 * @return ScheduledFuture
	 */
	private ScheduledFuture<?> startHeartbeat(final WebSocket websocket, final int heartbeatInterval) {
		this.heartbeatAckReceived = true;
		//returns some scheduled future from sending this heartbeat after delay
		return this.threadpool.scheduleAtFixedRate(() -> {
			if(this.heartbeatAckReceived) {
				this.heartbeatAckReceived = false;
				sendHeartbeat(websocket);
			} else {
				websocket.sendClose(WebSocketCloseCode.UNACCEPTABLE,"Heartbeat ACK not received.");
			}
		}, 0, heartbeatInterval, TimeUnit.MILLISECONDS);
	}

	/**
	 * Sends a heartbeat back to the websocket
	 * @param websocket - websocket
	 */
	private void sendHeartbeat(WebSocket websocket) {
		JsonPacket heartbeatPacket = new JsonPacket();
		heartbeatPacket.put("op", GatewayOpcode.HEARTBEAT.getOpcode());
		heartbeatPacket.put("d", lastSeq);
		LOGGER.info("Sending heartbeat response.");
		WebSocketFrame heartbeatFrame = WebSocketFrame.createTextFrame(heartbeatPacket.toString());
		websocket.sendFrame(heartbeatFrame);
	}

	/**
	 * Sends identify back to the websocket
	 * format:
	 *  {
	 *      "op": 2
	 *     	"d": {
	 *      	"token": "my_token",
	 *			"properties": {
	 *				"$os": System.getProperty("os.name"),
	 *				"$browser": "kiyo",
	 *				"$device": "kiyo"
	 *			},
	 *			"compress": true,
	 *			"large_threshold": 250
	 *		}
	 *  }
	 * @param websocket - websocket
	 */
	private void sendIdentify(WebSocket websocket) {
		// creates json w/ fields {op, d { ... }, ... }, can nest JsonPackets for json within json
		JsonPacket identifyPacket = new JsonPacket();
		identifyPacket.put("op", GatewayOpcode.IDENTIFY.getOpcode());

		JsonPacket data = new JsonPacket();
		data.put("token", this.token);

		JsonPacket properties = new JsonPacket();
		properties.put("$os", System.getProperty("os.name"));
		properties.put("$browser", "kiyo");
		properties.put("$device", "kiyo");
		data.put("properties", properties);
		data.put("compress", true);
		data.put("large_threshold", 250);
		identifyPacket.put("d", data);

		WebSocketFrame identifyFrame = WebSocketFrame.createTextFrame(identifyPacket.toString());
		LOGGER.debug("Sending IDENTIFY response.");
		websocket.sendFrame(identifyFrame);
	}
}
