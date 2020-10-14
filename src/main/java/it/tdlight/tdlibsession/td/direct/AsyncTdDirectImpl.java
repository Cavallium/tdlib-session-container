package it.tdlight.tdlibsession.td.direct;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import it.tdlight.common.TelegramClient;
import it.tdlight.jni.TdApi;
import it.tdlight.jni.TdApi.AuthorizationStateClosed;
import it.tdlight.jni.TdApi.Function;
import it.tdlight.jni.TdApi.Object;
import it.tdlight.jni.TdApi.Update;
import it.tdlight.jni.TdApi.UpdateAuthorizationState;
import it.tdlight.tdlibsession.td.TdResult;
import it.tdlight.tdlight.ClientManager;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.EmitterProcessor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

public class AsyncTdDirectImpl implements AsyncTdDirect {

	private static final Logger logger = LoggerFactory.getLogger(AsyncTdDirect.class);

	private final AtomicReference<TelegramClient> td = new AtomicReference<>();
	private final Scheduler tdScheduler = Schedulers.newSingle("TdMain");
	private final Scheduler tdPollScheduler = Schedulers.newSingle("TdPoll");
	private final Scheduler tdUpdatesScheduler = Schedulers.newSingle("TdUpdate");
	private final Scheduler tdResponsesScheduler = Schedulers.newSingle("TdResponse");

	private final EmitterProcessor<AsyncResult<TdResult<Update>>> updatesProcessor = EmitterProcessor.create();
	private final String botAlias;

	public AsyncTdDirectImpl(String botAlias) {
		this.botAlias = botAlias;
	}

	@Override
	public <T extends TdApi.Object> Mono<TdResult<T>> execute(Function request, boolean synchronous) {
		if (synchronous) {
			return Mono.just(TdResult.of(this.td.get().execute(request)));
		} else {
			return Mono.<TdResult<T>>create(sink -> {
				try {
					this.td.get().send(request, v -> {
						sink.success(TdResult.of(v));
					}, sink::error);
				} catch (Throwable t) {
					sink.error(t);
				}
			}).subscribeOn(tdResponsesScheduler);
		}
	}

	@Override
	public Flux<AsyncResult<TdResult<Update>>> getUpdates(Duration receiveDuration, int eventsSize) {
		return Flux.from(updatesProcessor.subscribeOn(tdUpdatesScheduler));
	}

	public Scheduler getTdUpdatesScheduler() {
		return tdUpdatesScheduler;
	}

	public Scheduler getTdResponsesScheduler() {
		return tdResponsesScheduler;
	}

	@Override
	public Mono<Void> initializeClient() {
		return Mono.<Boolean>create(sink -> {
			Flux.<AsyncResult<TdResult<Update>>>create(emitter -> {
				var client = ClientManager.create((Object object) -> {
					emitter.next(Future.succeededFuture(TdResult.of(object)));
					// Close the emitter if receive closed state
					if (object.getConstructor() == UpdateAuthorizationState.CONSTRUCTOR
							&& ((UpdateAuthorizationState) object).authorizationState.getConstructor()
							== AuthorizationStateClosed.CONSTRUCTOR) {
						emitter.complete();
					}
				}, updateError -> {
					emitter.next(Future.failedFuture(updateError));
				}, error -> {
					emitter.next(Future.failedFuture(error));
				});
				this.td.set(client);

				emitter.onDispose(() -> {
					this.td.set(null);
				});
			}).subscribeOn(tdPollScheduler).subscribe(next -> {
				updatesProcessor.onNext(next);
				sink.success(true);
			}, error -> {
				updatesProcessor.onError(error);
				sink.error(error);
			}, () -> {
				updatesProcessor.onComplete();
				sink.success(true);
			});
		}).single().then().subscribeOn(tdScheduler);
	}

	@Override
	public Mono<Void> destroyClient() {
		return Mono.fromCallable(() -> {
			// do nothing
			return (Void) null;
		}).single().subscribeOn(tdScheduler);
	}
}