package it.tdlight.tdlibsession.td.middle;

import com.hazelcast.config.Config;
import com.hazelcast.config.EvictionPolicy;
import com.hazelcast.config.GroupConfig;
import com.hazelcast.config.MapConfig;
import com.hazelcast.config.MaxSizeConfig;
import com.hazelcast.config.MaxSizeConfig.MaxSizePolicy;
import com.hazelcast.config.MergePolicyConfig;
import com.hazelcast.config.SemaphoreConfig;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.eventbus.Message;
import io.vertx.core.eventbus.MessageCodec;
import io.vertx.core.eventbus.MessageConsumer;
import io.vertx.core.http.ClientAuth;
import io.vertx.core.net.JksOptions;
import io.vertx.core.spi.cluster.ClusterManager;
import io.vertx.spi.cluster.hazelcast.HazelcastClusterManager;
import java.nio.channels.AlreadyBoundException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jetbrains.annotations.Nullable;
import it.tdlight.utils.MonoUtils;
import reactor.core.publisher.Mono;

public class TdClusterManager {

	private static final AtomicBoolean definedMasterCluster = new AtomicBoolean(false);
	private static final AtomicBoolean definedNodesCluster = new AtomicBoolean(false);
	private final ClusterManager mgr;
	private final VertxOptions vertxOptions;
	private final Vertx vertx;
	private final EventBus eb;

	public TdClusterManager(ClusterManager mgr, VertxOptions vertxOptions, Vertx vertx, EventBus eventBus) {
		this.mgr = mgr;
		this.vertxOptions = vertxOptions;
		this.vertx = vertx;
		this.eb = eventBus;
	}

	public static Mono<TdClusterManager> ofMaster(JksOptions keyStoreOptions, JksOptions trustStoreOptions, boolean onlyLocal, String masterHostname, String netInterface, int port, Set<String> nodesAddresses) {
		if (definedMasterCluster.compareAndSet(false, true)) {
			var vertxOptions = new VertxOptions();
			netInterface = onlyLocal ? "127.0.0.1" : netInterface;
			Config cfg;
			if (!onlyLocal) {
				cfg = new Config();
				cfg.setInstanceName("Master");
			} else {
				cfg = null;
			}
			return of(cfg,
					vertxOptions,
					keyStoreOptions, trustStoreOptions, masterHostname, netInterface, port, nodesAddresses);
		} else {
			return Mono.error(new AlreadyBoundException());
		}
	}

	public static Mono<TdClusterManager> ofNodes(JksOptions keyStoreOptions, JksOptions trustStoreOptions, boolean onlyLocal, String masterHostname, String netInterface, int port, Set<String> nodesAddresses) {
		if (definedNodesCluster.compareAndSet(false, true)) {
			var vertxOptions = new VertxOptions();
			netInterface = onlyLocal ? "127.0.0.1" : netInterface;
			Config cfg;
			if (!onlyLocal) {
				cfg = new Config();
				cfg.setInstanceName("Node-" + new Random().nextLong());
			} else {
				cfg = null;
			}
			return of(cfg, vertxOptions, keyStoreOptions, trustStoreOptions, masterHostname, netInterface, port, nodesAddresses);
		} else {
			return Mono.error(new AlreadyBoundException());
		}
	}

	public static Mono<TdClusterManager> of(@Nullable Config cfg,
			VertxOptions vertxOptions,
			JksOptions keyStoreOptions,
			JksOptions trustStoreOptions,
			String masterHostname,
			String netInterface,
			int port,
			Set<String> nodesAddresses) {
		ClusterManager mgr;
		if (cfg != null) {
			cfg.getNetworkConfig().setPortCount(1);
			cfg.getNetworkConfig().setPort(port);
			cfg.getNetworkConfig().setPortAutoIncrement(false);
			cfg.getPartitionGroupConfig().setEnabled(false);
			cfg.addMapConfig(new MapConfig()
					.setName("__vertx.subs")
					.setBackupCount(1)
					.setTimeToLiveSeconds(0)
					.setMaxIdleSeconds(0)
					.setEvictionPolicy(EvictionPolicy.NONE)
					.setMaxSizeConfig(new MaxSizeConfig().setMaxSizePolicy(MaxSizePolicy.PER_NODE).setSize(0))
					.setMergePolicyConfig(new MergePolicyConfig().setPolicy("com.hazelcast.map.merge.LatestUpdateMapMergePolicy")));
			cfg.setSemaphoreConfigs(Map.of("__vertx.*", new SemaphoreConfig().setInitialPermits(1)));
			cfg.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);
			cfg.getNetworkConfig().getJoin().getAwsConfig().setEnabled(false);
			cfg.getNetworkConfig().getJoin().getTcpIpConfig().setEnabled(true);
			var addresses = new ArrayList<>(nodesAddresses);
			cfg.getNetworkConfig().getJoin().getTcpIpConfig().setMembers(addresses);
			cfg.getNetworkConfig().getInterfaces().clear();
			cfg.getNetworkConfig().getInterfaces().setInterfaces(Collections.singleton(netInterface)).setEnabled(true);
			cfg.getNetworkConfig().setOutboundPorts(Collections.singleton(0));

			cfg.setProperty("hazelcast.logging.type", "slf4j");
			cfg.setProperty("hazelcast.wait.seconds.before.join", "0");
			cfg.setProperty("hazelcast.tcp.join.port.try.count", "5");
			cfg.setProperty("hazelcast.socket.bind.any", "false");
			cfg.setGroupConfig(new GroupConfig().setName("dev").setPassword("HzPasswordsAreDeprecated"));
			mgr = new HazelcastClusterManager(cfg);
			vertxOptions.setClusterManager(mgr);
			vertxOptions.getEventBusOptions().setConnectTimeout(120000);
			//vertxOptions.getEventBusOptions().setIdleTimeout(60);
			//vertxOptions.getEventBusOptions().setSsl(false);

			vertxOptions.getEventBusOptions().setSslHandshakeTimeout(120000).setSslHandshakeTimeoutUnit(TimeUnit.MILLISECONDS);
			vertxOptions.getEventBusOptions().setKeyStoreOptions(keyStoreOptions);
			vertxOptions.getEventBusOptions().setTrustStoreOptions(trustStoreOptions);
			vertxOptions.getEventBusOptions().setHost(masterHostname);
			vertxOptions.getEventBusOptions().setPort(port + 1);
			vertxOptions.getEventBusOptions().setSsl(true).setEnabledSecureTransportProtocols(Set.of("TLSv1.3", "TLSv1.2"));
			vertxOptions.getEventBusOptions().setClientAuth(ClientAuth.REQUIRED);
		} else {
			mgr = null;
			vertxOptions.setClusterManager(null);
			vertxOptions.getEventBusOptions().setClustered(false);
		}

		return Mono
				.<Vertx>create(sink -> {
					if (mgr != null) {
						Vertx.clusteredVertx(vertxOptions, MonoUtils.toHandler(sink));
					} else {
						sink.success(Vertx.vertx(vertxOptions));
					}
				})
				.map(vertx -> new TdClusterManager(mgr, vertxOptions, vertx, vertx.eventBus()));
	}

	public Vertx getVertx() {
		return vertx;
	}

	public EventBus getEventBus() {
		return eb;
	}

	public VertxOptions getVertxOptions() {
		return vertxOptions;
	}

	public DeliveryOptions newDeliveryOpts() {
		return new DeliveryOptions().setSendTimeout(120000);
	}

	/**
	 *
	 * @param objectClass
	 * @param messageCodec
	 * @param <T>
	 * @return true if registered, false if already registered
	 */
	public <T> boolean registerDefaultCodec(Class<T> objectClass, MessageCodec<T, T> messageCodec) {
		try {
			eb.registerDefaultCodec(objectClass, messageCodec);
			return true;
		} catch (IllegalStateException ex) {
			if (ex.getMessage().startsWith("Already a default codec registered for class")) {
				return false;
			}
			if (ex.getMessage().startsWith("Already a codec registered with name")) {
				return false;
			}
			throw ex;
		}
	}

	/**
	 * Create a message consumer against the specified address.
	 * <p>
	 * The returned consumer is not yet registered
	 * at the address, registration will be effective when {@link MessageConsumer#handler(io.vertx.core.Handler)}
	 * is called.
	 *
	 * @param address  the address that it will register it at
	 * @param localOnly if you want to receive only local messages
	 * @return the event bus message consumer
	 */
	public <T> MessageConsumer<T> consumer(String address, boolean localOnly) {
		if (localOnly) {
			return eb.localConsumer(address);
		} else {
			return eb.consumer(address);
		}
	}

	/**
	 * Create a consumer and register it against the specified address.
	 *
	 * @param address  the address that will register it at
	 * @param localOnly if you want to receive only local messages
	 * @param handler  the handler that will process the received messages
	 *
	 * @return the event bus message consumer
	 */
	public <T> MessageConsumer<T> consumer(String address, boolean localOnly, Handler<Message<T>> handler) {
		if (localOnly) {
			return eb.localConsumer(address, handler);
		} else {
			return eb.consumer(address, handler);
		}
	}
}