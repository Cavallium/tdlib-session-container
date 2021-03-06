package it.tdlight.tdlibsession.td.direct;

import io.vertx.core.json.JsonObject;
import it.tdlight.jni.TdApi;
import it.tdlight.jni.TdApi.AuthorizationStateClosed;
import it.tdlight.jni.TdApi.Close;
import it.tdlight.jni.TdApi.Function;
import it.tdlight.jni.TdApi.Ok;
import it.tdlight.jni.TdApi.UpdateAuthorizationState;
import it.tdlight.tdlibsession.td.ReactorTelegramClient;
import it.tdlight.tdlibsession.td.TdError;
import it.tdlight.tdlibsession.td.TdResult;
import it.tdlight.utils.MonoUtils;
import java.time.Duration;
import org.warp.commonutils.log.Logger;
import org.warp.commonutils.log.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.publisher.Sinks.One;
import reactor.core.scheduler.Schedulers;

public class AsyncTdDirectImpl implements AsyncTdDirect {

	private static final Logger logger = LoggerFactory.getLogger(AsyncTdDirect.class);

	private final TelegramClientFactory telegramClientFactory;
	private final JsonObject implementationDetails;
	private final String botAlias;

	private final One<ReactorTelegramClient> td = Sinks.one();

	public AsyncTdDirectImpl(TelegramClientFactory telegramClientFactory,
			JsonObject implementationDetails,
			String botAlias) {
		this.telegramClientFactory = telegramClientFactory;
		this.implementationDetails = implementationDetails;
		this.botAlias = botAlias;
	}

	@Override
	public <T extends TdApi.Object> Mono<TdResult<T>> execute(Function request, boolean synchronous) {
		if (synchronous) {
			return Mono
					.firstWithSignal(td.asMono(), Mono.empty())
					.single()
					.timeout(Duration.ofSeconds(5))
					.flatMap(td -> MonoUtils.fromBlockingSingle(() -> {
						logger.trace("Sending execute to TDLib {}", request);
						TdResult<T> result = TdResult.of(td.execute(request));
						logger.trace("Received execute response from TDLib. Request was {}", request);
						return result;
					}))
					.single();
		} else {
			return Mono
					.firstWithSignal(td.asMono(), Mono.empty())
					.single()
					.timeout(Duration.ofSeconds(5))
					.<TdResult<T>>flatMap(td -> {
						if (td != null) {
							return Mono
									.fromRunnable(() -> logger.trace("Sending request to TDLib {}", request))
									.then(td.send(request))
									.single()
									.<TdResult<T>>map(TdResult::of)
									.doOnSuccess(s -> logger.trace("Sent request to TDLib {}", request));
						} else {
							return Mono.fromCallable(() -> {
								if (request.getConstructor() == Close.CONSTRUCTOR) {
									logger.trace("Sending close success to request {}", request);
									return TdResult.of(new Ok());
								} else {
									logger.trace("Sending close error to request {} ", request);
									throw new IllegalStateException("TDLib client is destroyed");
								}
							});
						}
					})
					.single();
		}
	}

	@Override
	public Mono<Void> initialize() {
		return Mono
				.fromRunnable(() -> logger.trace("Initializing"))
				.then(telegramClientFactory.create(implementationDetails))
				.flatMap(reactorTelegramClient -> reactorTelegramClient.initialize().thenReturn(reactorTelegramClient))
				.flatMap(client -> {
					if (td.tryEmitValue(client).isFailure()) {
						return Mono.error(new TdError(500, "Failed to emit td client"));
					}
					return Mono.just(client);
				})
				.doOnNext(client -> client.execute(new TdApi.SetLogVerbosityLevel(1)))
				.doOnSuccess(s -> logger.trace("Initialized"))
				.then();
	}

	@Override
	public Flux<TdApi.Object> receive(AsyncTdDirectOptions options) {
		// If closed it will be either true or false
		final One<Boolean> closedFromTd = Sinks.one();
		return Mono
				.firstWithSignal(td.asMono(), Mono.empty())
				.single()
				.timeout(Duration.ofSeconds(5))
				.flatMapMany(ReactorTelegramClient::receive)
				.doOnNext(update -> {
					// Close the emitter if receive closed state
					if (update.getConstructor() == UpdateAuthorizationState.CONSTRUCTOR
							&& ((UpdateAuthorizationState) update).authorizationState.getConstructor()
							== AuthorizationStateClosed.CONSTRUCTOR) {
						logger.debug("Received closed status from tdlib");
						closedFromTd.tryEmitValue(true);
					}
				})
				.doOnCancel(() -> {
					// Try to emit false, so that if it has not been closed from tdlib, now it is explicitly false.
					closedFromTd.tryEmitValue(false);
				})
				.subscribeOn(Schedulers.parallel());
	}
}
