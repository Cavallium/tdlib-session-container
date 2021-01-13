package it.tdlight.tdlibsession.td.middle.direct;

import it.tdlight.jni.TdApi;
import it.tdlight.jni.TdApi.Function;
import it.tdlight.jni.TdApi.Object;
import it.tdlight.tdlibsession.td.TdResult;
import it.tdlight.tdlibsession.td.direct.AsyncTdDirectImpl;
import it.tdlight.tdlibsession.td.middle.AsyncTdMiddle;
import it.tdlight.tdlibsession.td.middle.TdClusterManager;
import it.tdlight.tdlibsession.td.middle.client.AsyncTdMiddleEventBusClient;
import it.tdlight.tdlibsession.td.middle.server.AsyncTdMiddleEventBusServer;
import java.util.Objects;
import org.warp.commonutils.error.InitializationException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.ReplayProcessor;

public class AsyncTdMiddleLocal implements AsyncTdMiddle {

	private final AsyncTdDirectImpl td;
	private final AsyncTdMiddleEventBusServer srv;
	private final TdClusterManager masterClusterManager;
	private ReplayProcessor<AsyncTdMiddleEventBusClient> cli = ReplayProcessor.cacheLast();
	private final String botAlias;
	private final String botAddress;

	public AsyncTdMiddleLocal(TdClusterManager masterClusterManager, String botAlias, String botAddress) throws InitializationException {
		this.td = new AsyncTdDirectImpl(botAlias);
		this.srv = new AsyncTdMiddleEventBusServer(masterClusterManager);
		this.masterClusterManager = masterClusterManager;
		this.botAlias = botAlias;
		this.botAddress = botAddress;
	}

	public Mono<AsyncTdMiddleLocal> start() {
		return srv.start(botAddress, botAlias, true).onErrorMap(InitializationException::new).flatMap(_x -> {
			try {
				return AsyncTdMiddleEventBusClient.getAndDeployInstance(masterClusterManager, botAlias, botAddress, true).doOnNext(cli -> {
					this.cli.onNext(cli);
				}).doOnError(error -> this.cli.onError(error)).doFinally(_v -> this.cli.onComplete());
			} catch (InitializationException e) {
				this.cli.onError(e);
				return Mono.error(e);
			}
		}).map(v -> this);
	}

	@Override
	public Flux<TdApi.Object> receive() {
		return cli.filter(Objects::nonNull).single().flatMapMany(AsyncTdMiddleEventBusClient::receive);
	}

	@Override
	public <T extends Object> Mono<TdResult<T>> execute(Function request, boolean executeDirectly) {
		return cli
				.filter(obj -> Objects.nonNull(obj))
				.single()
				.flatMap(c -> c.<T>execute(request, executeDirectly));
	}
}
