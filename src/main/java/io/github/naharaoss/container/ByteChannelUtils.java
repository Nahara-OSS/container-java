package io.github.naharaoss.container;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.Locale;

import org.jspecify.annotations.NullMarked;

@NullMarked
public final class ByteChannelUtils {
	public static void readFully(ReadableByteChannel channel, ByteBuffer buffer) throws IOException {
		while (buffer.hasRemaining()) {
			if (channel.read(buffer) == -1) throw new IOException(String.format(Locale.getDefault(), "EOF while reading %d more bytes", buffer.remaining()));
		}
	}

	public static void writeFully(WritableByteChannel channel, ByteBuffer buffer) throws IOException {
		while (buffer.hasRemaining()) channel.write(buffer);
	}
}
