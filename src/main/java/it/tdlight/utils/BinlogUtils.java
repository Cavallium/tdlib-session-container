package it.tdlight.utils;

import io.vertx.core.file.OpenOptions;
import io.vertx.reactivex.core.buffer.Buffer;
import io.vertx.reactivex.core.file.FileSystem;
import java.nio.file.Path;
import java.text.CharacterIterator;
import java.text.StringCharacterIterator;
import java.time.Instant;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;

public class BinlogUtils {

	private static final Logger logger = LoggerFactory.getLogger(BinlogUtils.class);

	public static Mono<BinlogAsyncFile> retrieveBinlog(FileSystem vertxFilesystem, Path binlogPath) {
		var path = binlogPath.toString();
		var openOptions = new OpenOptions().setWrite(true).setRead(true).setCreate(false).setDsync(true);
		return vertxFilesystem
				// Create file if not exist to avoid errors
				.rxExists(path).filter(exists -> exists).as(MonoUtils::toMono)
				.switchIfEmpty(Mono.defer(() -> vertxFilesystem.rxMkdirs(binlogPath.getParent().toString()).as(MonoUtils::toMono))
						.then(vertxFilesystem.rxCreateFile(path).as(MonoUtils::toMono))
						.thenReturn(true)
				)
				// Open file
				.flatMap(x -> vertxFilesystem.rxOpen(path, openOptions).as(MonoUtils::toMono))
				.map(file -> new BinlogAsyncFile(vertxFilesystem, path, file))
				.single();
	}

	public static Mono<Void> saveBinlog(BinlogAsyncFile binlog, byte[] data) {
		return binlog.overwrite(data);
	}

	public static Mono<Void> chooseBinlog(FileSystem vertxFilesystem,
			Path binlogPath,
			byte[] remoteBinlog,
			long remoteBinlogDate) {
		var path = binlogPath.toString();
		return retrieveBinlog(vertxFilesystem, binlogPath)
				.flatMap(binlog -> Mono
						.just(binlog)
						.zipWith(binlog.getLastModifiedTime())
				)
				.doOnSuccess(s -> logger.info("Local binlog: " + binlogPath + ". Local date: " + Instant.ofEpochMilli(s == null ? 0 : s.getT2()).atZone(ZoneOffset.UTC).toString() + " Remote date: " + Instant.ofEpochMilli(remoteBinlogDate).atZone(ZoneOffset.UTC).toString()))
				// Files older than the remote file will be overwritten
				.filter(tuple -> tuple.getT2() >= remoteBinlogDate)
				.doOnNext(v -> logger.info("Using local binlog: " + binlogPath))
				.map(Tuple2::getT1)
				.switchIfEmpty(Mono.defer(() -> Mono.fromRunnable(() -> logger.info("Using remote binlog. Overwriting " + binlogPath)))
						.then(vertxFilesystem.rxWriteFile(path, Buffer.buffer(remoteBinlog)).as(MonoUtils::toMono))
						.then(retrieveBinlog(vertxFilesystem, binlogPath))
				)
				.single()
				.then();
	}

	public static Mono<Void> cleanSessionPath(FileSystem vertxFilesystem, Path binlogPath, Path sessionPath) {
		return vertxFilesystem
				.rxReadFile(binlogPath.toString()).as(MonoUtils::toMono)
				.flatMap(buffer -> vertxFilesystem
						.rxReadDir(sessionPath.toString(), "^(?!td.binlog$).*").as(MonoUtils::toMono)
						.flatMapMany(Flux::fromIterable)
						.doOnNext(file -> logger.debug("Deleting file {}", file))
						.flatMap(file -> vertxFilesystem.rxDeleteRecursive(file, true).as(MonoUtils::toMono))
						.onErrorResume(ex -> Mono.empty())
						.then()
				);
	}

	public static String humanReadableByteCountBin(long bytes) {
		long absB = bytes == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(bytes);
		if (absB < 1024) {
			return bytes + " B";
		}
		long value = absB;
		CharacterIterator ci = new StringCharacterIterator("KMGTPE");
		for (int i = 40; i >= 0 && absB > 0xfffccccccccccccL >> i; i -= 10) {
			value >>= 10;
			ci.next();
		}
		value *= Long.signum(bytes);
		return String.format("%.1f %ciB", value / 1024.0, ci.current());
	}
}