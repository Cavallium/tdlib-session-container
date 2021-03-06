package it.tdlight.tdlibsession;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.MessageCodec;
import it.tdlight.utils.VertxBufferInputStream;
import it.tdlight.utils.VertxBufferOutputStream;
import java.nio.charset.StandardCharsets;
import org.warp.commonutils.stream.SafeDataInputStream;
import org.warp.commonutils.stream.SafeDataOutputStream;

public class SignalMessageCodec<T> implements MessageCodec<SignalMessage<T>, SignalMessage<T>> {

	private final String codecName;
	private final MessageCodec<T, T> typeCodec;

	public SignalMessageCodec(MessageCodec<T, T> typeCodec) {
		super();
		this.codecName = "SignalCodec-" + typeCodec.name();
		this.typeCodec = typeCodec;
	}

	@Override
	public void encodeToWire(Buffer buffer, SignalMessage<T> t) {
		try (var bos = new VertxBufferOutputStream(buffer)) {
			try (var dos = new SafeDataOutputStream(bos)) {
				switch (t.getSignalType()) {
					case ITEM:
						dos.writeByte(0x01);
						break;
					case ERROR:
						dos.writeByte(0x02);
						break;
					case COMPLETE:
						dos.writeByte(0x03);
						break;
					default:
						throw new IllegalStateException("Unexpected value: " + t.getSignalType());
				}
			}
			switch (t.getSignalType()) {
				case ITEM:
					typeCodec.encodeToWire(buffer, t.getItem());
					break;
				case ERROR:
					var stringBytes = t.getErrorMessage().getBytes(StandardCharsets.UTF_8);
					buffer.appendInt(stringBytes.length);
					buffer.appendBytes(stringBytes);
					break;
			}
		}
	}

	@Override
	public SignalMessage<T> decodeFromWire(int pos, Buffer buffer) {
		try (var fis = new VertxBufferInputStream(buffer, pos)) {
			try (var dis = new SafeDataInputStream(fis)) {
				switch (dis.readByte()) {
					case 0x01:
						return SignalMessage.onNext(typeCodec.decodeFromWire(pos + 1, buffer));
					case 0x02:
						var size = dis.readInt();
						return SignalMessage.onDecodedError(new String(dis.readNBytes(size), StandardCharsets.UTF_8));
					case 0x03:
						return SignalMessage.onComplete();
					default:
						throw new IllegalStateException("Unexpected value: " + dis.readByte());
				}
			}
		}
	}

	@Override
	public SignalMessage<T> transform(SignalMessage<T> t) {
		// If a message is sent *locally* across the event bus.
		// This sends message just as is
		return t;
	}

	@Override
	public String name() {
		return codecName;
	}

	@Override
	public byte systemCodecID() {
		// Always -1
		return -1;
	}
}
