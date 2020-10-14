package it.tdlight.tdlibsession.td.easy;

import it.tdlight.common.utils.ScannerUtils;
import it.tdlight.jni.TdApi;
import it.tdlight.jni.TdApi.AuthorizationState;
import it.tdlight.jni.TdApi.AuthorizationStateClosed;
import it.tdlight.jni.TdApi.AuthorizationStateClosing;
import it.tdlight.jni.TdApi.AuthorizationStateReady;
import it.tdlight.jni.TdApi.AuthorizationStateWaitCode;
import it.tdlight.jni.TdApi.AuthorizationStateWaitEncryptionKey;
import it.tdlight.jni.TdApi.AuthorizationStateWaitPassword;
import it.tdlight.jni.TdApi.AuthorizationStateWaitPhoneNumber;
import it.tdlight.jni.TdApi.AuthorizationStateWaitRegistration;
import it.tdlight.jni.TdApi.AuthorizationStateWaitTdlibParameters;
import it.tdlight.jni.TdApi.CheckAuthenticationBotToken;
import it.tdlight.jni.TdApi.CheckAuthenticationPassword;
import it.tdlight.jni.TdApi.CheckDatabaseEncryptionKey;
import it.tdlight.jni.TdApi.Error;
import it.tdlight.jni.TdApi.Object;
import it.tdlight.jni.TdApi.OptionValue;
import it.tdlight.jni.TdApi.OptionValueBoolean;
import it.tdlight.jni.TdApi.OptionValueEmpty;
import it.tdlight.jni.TdApi.OptionValueInteger;
import it.tdlight.jni.TdApi.OptionValueString;
import it.tdlight.jni.TdApi.PhoneNumberAuthenticationSettings;
import it.tdlight.jni.TdApi.RegisterUser;
import it.tdlight.jni.TdApi.SetAuthenticationPhoneNumber;
import it.tdlight.jni.TdApi.SetTdlibParameters;
import it.tdlight.jni.TdApi.TdlibParameters;
import it.tdlight.jni.TdApi.Update;
import it.tdlight.jni.TdApi.UpdateAuthorizationState;
import it.tdlight.tdlibsession.FatalErrorType;
import it.tdlight.tdlibsession.td.TdResult;
import it.tdlight.tdlibsession.td.middle.AsyncTdMiddle;
import it.tdlight.utils.MonoUtils;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Set;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.warp.commonutils.error.InitializationException;
import reactor.core.publisher.EmitterProcessor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.ReplayProcessor;
import reactor.core.scheduler.Schedulers;

public class AsyncTdEasy {

	private static final Logger logger = LoggerFactory.getLogger(AsyncTdEasy.class);

	private final ReplayProcessor<AuthorizationState> authState = ReplayProcessor.cacheLastOrDefault(new AuthorizationStateClosed());
	private final ReplayProcessor<Boolean> requestedDefinitiveExit = ReplayProcessor.cacheLastOrDefault(false);
	private final ReplayProcessor<TdEasySettings> settings = ReplayProcessor.cacheLast();
	private final EmitterProcessor<Error> globalErrors = EmitterProcessor.create();
	private final EmitterProcessor<FatalErrorType> fatalErrors = EmitterProcessor.create();
	private final AsyncTdMiddle td;
	private final String logName;
	private final Flux<Update> incomingUpdatesCo;

	public AsyncTdEasy(AsyncTdMiddle td, String logName) {
		this.td = td;
		this.logName = logName;

		var sch = Schedulers.newSingle("TdEasyUpdates");

		// todo: use Duration.ZERO instead of 10ms interval
		this.incomingUpdatesCo = td.getUpdates()
				.filterWhen(update -> Mono.from(requestedDefinitiveExit).map(requestedDefinitiveExit -> !requestedDefinitiveExit))
				.subscribeOn(sch)
				.publishOn(sch)
				.flatMap(this::preprocessUpdates)
				.flatMap(update -> Mono.from(this.getState()).single().map(state -> new AsyncTdUpdateObj(state, update)))
				.filter(upd -> upd.getState().getConstructor() == AuthorizationStateReady.CONSTRUCTOR)
				.map(upd -> (TdApi.Update) upd.getUpdate())
				.doOnError(ex -> {
					logger.error(ex.getLocalizedMessage(), ex);
				}).doOnNext(v -> {
					if (logger.isDebugEnabled()) logger.debug(v.toString());
				}).doOnComplete(() -> {
					authState.onNext(new AuthorizationStateClosed());
				})
				.publish().refCount(1);
	}

	public Mono<Void> create(TdEasySettings settings) {
		return Mono
				.fromCallable(() -> {
					// Create session directories
					if (Files.notExists(Path.of(settings.databaseDirectory))) {
						try {
							Files.createDirectories(Path.of(settings.databaseDirectory));
						} catch (IOException ex) {
							throw new InitializationException(ex);
						}
					}
					return true;
				})
				.subscribeOn(Schedulers.boundedElastic())
				.flatMap(_v -> {
					this.settings.onNext(settings);
					return Mono.empty();
				});
	}

	/**
	 * Get TDLib state
	 */
	public Flux<AuthorizationState> getState() {
		return Flux.from(authState);
	}

	/**
	 * Get incoming updates from TDLib.
	 */
	public Flux<TdApi.Update> getIncomingUpdates() {
		return getIncomingUpdates(false);
	}

	private Flux<TdApi.Update> getIncomingUpdates(boolean includePreAuthUpdates) {
		return Flux.from(incomingUpdatesCo);
	}

	/**
	 * Get generic error updates from TDLib (When they are not linked to a precise request).
	 */
	public Flux<TdApi.Error> getIncomingErrors() {
		return Flux.from(globalErrors);
	}

	/**
	 * Receives fatal errors from TDLib.
	 */
	public Flux<FatalErrorType> getFatalErrors() {
		return Flux.from(fatalErrors);
	}

	/**
	 * Sends request to TDLib.
	 * @return The response or {@link TdApi.Error}.
	 */
	public <T extends Object> Mono<TdResult<T>> send(TdApi.Function request) {
		return td.<T>execute(request, false);
	}

	private <T extends TdApi.Object> Mono<TdResult<T>> sendDirectly(TdApi.Function obj) {
		return td.execute(obj, false);
	}

	/**
	 * Set verbosity level
	 * @param i level
	 */
	public Mono<Void> setVerbosityLevel(int i) {
		return MonoUtils.thenOrError(sendDirectly(new TdApi.SetLogVerbosityLevel(i)));
	}

	/**
	 * Clear option on TDLib
	 * @param name option name
	 */
	public Mono<Void> clearOption(String name) {
		return MonoUtils.thenOrError(sendDirectly(new TdApi.SetOption(name, new TdApi.OptionValueEmpty())));
	}

	/**
	 * Set option on TDLib
	 * @param name option name
	 * @param value option value
	 */
	public Mono<Void> setOptionString(String name, String value) {
		return MonoUtils.thenOrError(sendDirectly(new TdApi.SetOption(name, new TdApi.OptionValueString(value))));
	}

	/**
	 * Set option on TDLib
	 * @param name option name
	 * @param value option value
	 */
	public Mono<Void> setOptionInteger(String name, long value) {
		return MonoUtils.thenOrError(sendDirectly(new TdApi.SetOption(name, new TdApi.OptionValueInteger(value))));
	}

	/**
	 * Set option on TDLib
	 * @param name option name
	 * @param value option value
	 */
	public Mono<Void> setOptionBoolean(String name, boolean value) {
		return MonoUtils.thenOrError(sendDirectly(new TdApi.SetOption(name, new TdApi.OptionValueBoolean(value))));
	}

	/**
	 * Get option from TDLib
	 * @param name option name
	 * @return The value or nothing
	 */
	public Mono<String> getOptionString(String name) {
		return this.<TdApi.OptionValue>sendDirectly(new TdApi.GetOption(name)).<OptionValue>flatMap(MonoUtils::orElseThrow).flatMap((TdApi.OptionValue value) -> {
			switch (value.getConstructor()) {
				case OptionValueString.CONSTRUCTOR:
					return Mono.just(((OptionValueString) value).value);
				case OptionValueEmpty.CONSTRUCTOR:
					return Mono.empty();
				default:
					return Mono.error(new UnsupportedOperationException("The option " + name + " is of type "
							+ value.getClass().getSimpleName()));
			}
		});
	}

	/**
	 * Get option from TDLib
	 * @param name option name
	 * @return The value or nothing
	 */
	public Mono<Long> getOptionInteger(String name) {
		return this.<TdApi.OptionValue>sendDirectly(new TdApi.GetOption(name)).<TdApi.OptionValue>flatMap(MonoUtils::orElseThrow).flatMap((TdApi.OptionValue value) -> {
			switch (value.getConstructor()) {
				case OptionValueInteger.CONSTRUCTOR:
					return Mono.just(((OptionValueInteger) value).value);
				case OptionValueEmpty.CONSTRUCTOR:
					return Mono.empty();
				default:
					return Mono.error(new UnsupportedOperationException("The option " + name + " is of type "
							+ value.getClass().getSimpleName()));
			}
		});
	}

	/**
	 * Get option from TDLib
	 * @param name option name
	 * @return The value or nothing
	 */
	public Mono<Boolean> getOptionBoolean(String name) {
		return this.<TdApi.OptionValue>sendDirectly(new TdApi.GetOption(name)).<TdApi.OptionValue>flatMap(MonoUtils::orElseThrow).flatMap((TdApi.OptionValue value) -> {
			switch (value.getConstructor()) {
				case OptionValueBoolean.CONSTRUCTOR:
					return Mono.just(((OptionValueBoolean) value).value);
				case OptionValueEmpty.CONSTRUCTOR:
					return Mono.empty();
				default:
					return Mono.error(new UnsupportedOperationException("The option " + name + " is of type "
							+ value.getClass().getSimpleName()));
			}
		});
	}

	/**
	 * Synchronously executes TDLib requests. Only a few requests can be executed synchronously. May
	 * be called from any thread.
	 *
	 * @param request Request to the TDLib.
	 * @return The request response.
	 */
	public <T extends Object> Mono<TdResult<T>> execute(TdApi.Function request) {
		return td.execute(request, true);
	}

	/**
	 * Set if skip updates or not
	 */
	public Mono<Void> setSkipUpdates(boolean skipUpdates) { //todo: do this
		return null;
	}

	/**
	 * Closes the client gracefully by sending {@link TdApi.Close}.
	 */
	public Mono<Void> close() {
		return Mono.from(getState())
				.filter(state -> {
					switch (state.getConstructor()) {
						case AuthorizationStateClosing.CONSTRUCTOR:
						case AuthorizationStateClosed.CONSTRUCTOR:
							return false;
						default:
							return true;
					}
				})
				.then(Mono.from(requestedDefinitiveExit).single())
				.filter(closeRequested -> !closeRequested)
				.doOnSuccess(v -> requestedDefinitiveExit.onNext(true))
				.then(td.execute(new TdApi.Close(), false))
				.then();
	}

	/**
	 *
	 * @param timeout Timeout in seconds when reading data
	 */
	public void setReadTimeout(int timeout) {
		//todo: do this
	}

	/**
	 *
	 * @param timeout Timeout in seconds when listening methods or connecting
	 */
	public void setMethodTimeout(int timeout) {
		//todo: do this
	}

	private Mono<? extends Object> catchErrors(Object obj) {
		if (obj.getConstructor() == Error.CONSTRUCTOR) {
			var error = (Error) obj;

			switch (error.message) {
				case "PHONE_CODE_INVALID":
					globalErrors.onNext(error);
					return Mono.just(new AuthorizationStateWaitCode());
				case "PASSWORD_HASH_INVALID":
					globalErrors.onNext(error);
					return Mono.just(new AuthorizationStateWaitPassword());
				case "PHONE_NUMBER_INVALID":
					fatalErrors.onNext(FatalErrorType.PHONE_NUMBER_INVALID);
					break;
				case "ACCESS_TOKEN_INVALID":
					fatalErrors.onNext(FatalErrorType.ACCESS_TOKEN_INVALID);
					break;
				case "CONNECTION_KILLED":
					fatalErrors.onNext(FatalErrorType.CONNECTION_KILLED);
					break;
				default:
					globalErrors.onNext(error);
					break;
			}
			return Mono.empty();
		}
		return Mono.just(obj);
	}

	public Mono<Boolean> isBot() {
		return Mono.from(settings).single().map(TdEasySettings::isBotTokenSet);
	}

	private Publisher<Update> preprocessUpdates(Update updateObj) {
		return Mono
				.just(updateObj)
				.flatMap(this::catchErrors)
				.filter(obj -> obj.getConstructor() == UpdateAuthorizationState.CONSTRUCTOR)
				.map(obj -> ((UpdateAuthorizationState) obj).authorizationState)
				.flatMap(obj -> {
					this.authState.onNext(new AuthorizationStateReady());
					switch (obj.getConstructor()) {
						case AuthorizationStateWaitTdlibParameters.CONSTRUCTOR:
							return MonoUtils.thenOrError(Mono.from(this.settings).map(settings -> {
								var parameters = new TdlibParameters();
								parameters.useTestDc = settings.useTestDc;
								parameters.databaseDirectory = settings.databaseDirectory;
								parameters.filesDirectory = settings.filesDirectory;
								parameters.useFileDatabase = settings.useFileDatabase;
								parameters.useChatInfoDatabase = settings.useChatInfoDatabase;
								parameters.useMessageDatabase = settings.useMessageDatabase;
								parameters.useSecretChats = false;
								parameters.apiId = settings.apiId;
								parameters.apiHash = settings.apiHash;
								parameters.systemLanguageCode = settings.systemLanguageCode;
								parameters.deviceModel = settings.deviceModel;
								parameters.systemVersion = settings.systemVersion;
								parameters.applicationVersion = settings.applicationVersion;
								parameters.enableStorageOptimizer = settings.enableStorageOptimizer;
								parameters.ignoreFileNames = settings.ignoreFileNames;
								return new SetTdlibParameters(parameters);
							}).flatMap(this::sendDirectly));
						case AuthorizationStateWaitEncryptionKey.CONSTRUCTOR:
							return MonoUtils.thenOrError(sendDirectly(new CheckDatabaseEncryptionKey()));
						case AuthorizationStateWaitPhoneNumber.CONSTRUCTOR:
							return MonoUtils.thenOrError(Mono.from(this.settings).flatMap(settings -> {
								if (settings.isPhoneNumberSet()) {
									return sendDirectly(new SetAuthenticationPhoneNumber(String.valueOf(settings.getPhoneNumber()),
											new PhoneNumberAuthenticationSettings(false, false, false)
									));
								} else if (settings.isBotTokenSet()) {
									return sendDirectly(new CheckAuthenticationBotToken(settings.getBotToken()));
								} else {
									return Mono.error(new IllegalArgumentException("A bot is neither an user or a bot"));
								}
							}));
						case AuthorizationStateWaitRegistration.CONSTRUCTOR:
							var authorizationStateWaitRegistration = (AuthorizationStateWaitRegistration) obj;
							RegisterUser registerUser = new RegisterUser();
							if (authorizationStateWaitRegistration.termsOfService != null
									&& authorizationStateWaitRegistration.termsOfService.text != null && !authorizationStateWaitRegistration.termsOfService.text.text.isBlank()) {
								logger.info("Telegram Terms of Service:\n" + authorizationStateWaitRegistration.termsOfService.text.text);
							}

							while (registerUser.firstName == null || registerUser.firstName.length() <= 0
									|| registerUser.firstName.length() > 64 || registerUser.firstName.isBlank()) {
								registerUser.firstName = ScannerUtils.askParameter(this.logName, "Enter First Name").trim();
							}
							while (registerUser.lastName == null || registerUser.firstName.length() > 64) {
								registerUser.lastName = ScannerUtils.askParameter(this.logName, "Enter Last Name").trim();
							}

							return MonoUtils.thenOrError(sendDirectly(registerUser));
						case AuthorizationStateWaitPassword.CONSTRUCTOR:
							var authorizationStateWaitPassword = (AuthorizationStateWaitPassword) obj;
							String passwordMessage = "Password authorization of '" + this.logName + "':";
							if (authorizationStateWaitPassword.passwordHint != null && !authorizationStateWaitPassword.passwordHint.isBlank()) {
								passwordMessage += "\n\tHint: " + authorizationStateWaitPassword.passwordHint;
							}
							logger.info(passwordMessage);

							var password = ScannerUtils.askParameter(this.logName, "Enter your password");

							return MonoUtils.thenOrError(sendDirectly(new CheckAuthenticationPassword(password)));
						case AuthorizationStateReady.CONSTRUCTOR: {
							return Mono.empty();
						}
						case AuthorizationStateClosed.CONSTRUCTOR:
							return Mono.from(requestedDefinitiveExit).doOnNext(closeRequested -> {
								if (closeRequested) {
									logger.info("AsyncTdEasy closed successfully");
								} else {
									logger.warn("AsyncTdEasy closed unexpectedly: " + logName);
								}
							}).flatMap(closeRequested -> {
								if (closeRequested) {
									return Mono
											.from(settings)
											.map(settings -> settings.databaseDirectory)
											.map(Path::of)
											.flatMapIterable(sessionPath -> Set.of(sessionPath.resolve("media"),
													sessionPath.resolve("passport"),
													sessionPath.resolve("profile_photos"),
													sessionPath.resolve("stickers"),
													sessionPath.resolve("temp"),
													sessionPath.resolve("thumbnails"),
													sessionPath.resolve("wallpapers")
											))
											.doOnNext(directory -> {
												try {
													if (!Files.walk(directory)
															.sorted(Comparator.reverseOrder())
															.map(Path::toFile)
															.allMatch(File::delete)) {
														throw new IOException("Can't delete a file!");
													}
												} catch (IOException e) {
													logger.error("Can't delete temporary session subdirectory", e);
												}
											})
											.then(Mono.just(closeRequested));
								} else {
									return Mono.just(closeRequested);
								}
							}).then();
						default:
							return Mono.empty();
					}
				})
				.thenReturn(updateObj);
	}
}