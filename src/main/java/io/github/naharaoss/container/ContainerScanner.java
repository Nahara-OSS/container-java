package io.github.naharaoss.container;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;
import java.util.Arrays;

import org.jspecify.annotations.NullMarked;

import io.github.naharaoss.container.ContainerDocument.Data;

/**
 * <p>
 * Class for scanning container documents.
 * </p>
 *
 * <p>
 * The purpose of this class is to quickly read part of the document, such as scanning metadata or thumbnails for
 * example. This scanner only works on existing files. New files should be written with {@link ContainerDocument}
 * instead.
 * </p>
 *
 * @see #hasNext()
 * @see #next()
 */
@NullMarked
public class ContainerScanner implements Closeable {
	private SeekableByteChannel channel;
	private byte[] namespace;
	private long nextOffset;

	public ContainerScanner(SeekableByteChannel channel) throws IOException {
		this.channel = channel;

		ByteBuffer buffer = ByteBuffer.allocate(ContainerDocument.CONTAINER_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
		byte[] signature = new byte[ContainerDocument.SIGNATURE.length];

		channel.position(0);
		ByteChannelUtils.readFully(channel, buffer);
		buffer.flip();

		buffer.get(signature);
		buffer.getLong();
		nextOffset = buffer.getLong();
		namespace = new byte[0xFF & buffer.get()];

		if (!Arrays.equals(signature, ContainerDocument.SIGNATURE)) {
			throw new IOException("Signature is invalid");
		}

		if (namespace.length > ContainerDocument.NAMESPACE_SIZE) {
			throw new IOException(String.format("Namespace length is greater than %d bytes", ContainerDocument.NAMESPACE_SIZE));
		}

		buffer.get(namespace);
	}

	public byte[] getNamespace() {
		return namespace.clone();
	}

	public ContainerDocument.Data next() throws IOException {
		if (nextOffset == 0) throw new IndexOutOfBoundsException("No more chunks");

		ByteBuffer buffer = ByteBuffer.allocate(ContainerDocument.CHUNK_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
		long offset = nextOffset;
		int type;
		int size;

		channel.position(nextOffset);
		ByteChannelUtils.readFully(channel, buffer);
		buffer.flip();
		type = buffer.getInt();
		size = buffer.getInt();
		nextOffset = buffer.getLong();

		return new Data(offset, size, type);
	}

	/**
	 * <p>
	 * Check whether there is another chunk.
	 * </p>
	 *
	 * <p>
	 * If this method returns {@code true}, {@link #next()} can be called without throwing exception.
	 * </p>
	 *
	 * @return Whether there is another chunk
	 */
	public boolean hasNext() {
		return nextOffset != 0;
	}

	@Override
	public void close() throws IOException {
		channel.close();
	}
}
