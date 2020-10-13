package it.tdlight.tdlibsession.td;

import it.tdlight.jni.TdApi;
import it.tdlight.jni.TdApi.Error;
import it.tdlight.jni.TdApi.Object;

public class TdResultMessage {
	public final TdApi.Object value;
	public final TdApi.Error cause;

	public TdResultMessage(Object value, Error cause) {
		this.value = value;
		this.cause = cause;
	}

	public <T extends Object> TdResult<T> toTdResult() {
		if (value != null) {
			//noinspection unchecked
			return TdResult.succeeded((T) value);
		} else {
			return TdResult.failed(cause);
		}
	}
}
