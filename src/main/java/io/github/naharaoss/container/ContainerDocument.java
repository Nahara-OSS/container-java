package io.github.naharaoss.container;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * <p>
 * Implementation of Nahara's Container Base Specification for Java 8.
 * </p>
 *
 * <p>
 * Nahara's Container format is designed for editor-style applications that need to access the file on the disk
 * frequently (instead of loading entire content into memory). By accessing the content on the disk directly, the apps
 * can avoid filling up the system memory. The container format is initially designed for Nahara's Sketchpad, which is
 * an Android app for sketching, thus the existence of this Java 8 implementation.
 * </p>
 *
 * <p>
 * <b>Namespace</b>: Each file format that depends on Nahara's Container Base Specification should have its own
 * namespace ID, which is a string with up to 63 bytes long. The namespace may be used to store the file format version
 * and other properties that may not be modified frequently, but ideally properties should use its own chunk instead.
 * The chunk IDs are defined by namespace's registry.
 * </p>
 *
 * @see #loadOrInit(SeekableByteChannel, Supplier)
 * @see #init(SeekableByteChannel, byte[])
 * @see #load(SeekableByteChannel)
 * @see #allocate(int, int)
 * @see #delete(Data)
 */
@NullMarked
public class ContainerDocument implements Closeable {
	/* package-private */ static final byte[] SIGNATURE = "NaharaContainer1".getBytes(StandardCharsets.UTF_8);
	public static final int NAMESPACE_SIZE = 63; // 1 byte for namespace size
	public static final int ALIGNMENT = 16;

	/**
	 * <p>
	 * The size of container header in bytes. The container header contains the following fields:
	 * </p>
	 *
	 * <ol>
	 * <li>16 bytes - Container's file signature: {@code NaharaContainer1}</li>
	 * <li>8 bytes - Reserved</li>
	 * <li>8 bytes - Offset to the start of first chunk</li>
	 * <li>64 bytes - Namespace's length and namespace</li>
	 * </ol>
	 *
	 * <p>
	 * The offset to the start of first chunk must be greater than or equals to {@link #CONTAINER_HEADER_SIZE}, or use
	 * 0 to indicate that there is no chunks. The maximum length of namespace is 63 bytes.
	 * </p>
	 */
	public static final int CONTAINER_HEADER_SIZE = SIGNATURE.length + 8 + 8 + 1 + NAMESPACE_SIZE;

	/**
	 * <p>
	 * The size of chunk header in bytes. The chunk header contains the following fields:
	 * </p>
	 *
	 * <ol>
	 * <li>4 bytes - Chunk type (defined in namespace's registry)</li>
	 * <li>4 bytes - Chunk size (bytes)</li>
	 * <li>8 bytes - Offset to the start of next chunk</li>
	 * </ol>
	 *
	 * <p>
	 * The offset to the start of next chunk must be greater than or equals to the offset to the end of previous chunk.
	 * This implies the offset to next chunk cannot reference itself to create circular reference. If the offset is 0,
	 * there is no more chunks after current chunk.
	 * </p>
	 */
	public static final int CHUNK_HEADER_SIZE = 4 + 4 + 8;

	private SeekableByteChannel channel;
	private byte[] namespace;
	private ArrayList<Allocation> allocations;
	private Map<Data, Allocation> dataToAllocation = new HashMap<>();

	private ContainerDocument(SeekableByteChannel channel, byte[] namespace, ArrayList<Allocation> allocations) {
		this.channel = channel;
		this.namespace = namespace;
		this.allocations = allocations;

		for (Allocation allocation : allocations) {
			if (allocation.data == null) continue;
			dataToAllocation.put(allocation.data, allocation);
		}
	}

	/**
	 * <p>
	 * Load if the signature is valid, initialize otherwise.
	 * </p>
	 *
	 * @param channel The seekable channel, usually {@link FileChannel}
	 * @param namespaceFactory The factory to create namespace when initializing
	 * @return The container document wrapper
	 * @throws IOException When an error occurred during I/O
	 */
	public static ContainerDocument loadOrInit(SeekableByteChannel channel, Supplier<byte[]> namespaceFactory) throws IOException {
		Objects.requireNonNull(channel, "channel cannot be null");
		Objects.requireNonNull(namespaceFactory, "namespace cannot be null");

		ByteBuffer buffer = ByteBuffer.allocate(SIGNATURE.length);
		byte[] signature = new byte[SIGNATURE.length];
		boolean load = true;
		channel.position(0);

		while (buffer.hasRemaining()) {
			int result = channel.read(buffer);

			if (result == -1) {
				load = false;
				break;
			}
		}

		buffer.flip();

		if (load &= (buffer.remaining() == SIGNATURE.length)) {
			buffer.get(signature);
			load = Arrays.equals(signature, SIGNATURE);
		}

		return load ? load(channel) : init(channel, namespaceFactory.get());
	}

	/**
	 * <p>
	 * Initialize the document and return the container document wrapper.
	 * </p>
	 *
	 * @param channel   The seekable byte channel, usually {@link FileChannel}
	 * @param namespace The namespace of the document
	 * @return The container document wrapper
	 * @throws IOException              When an error occurred during I/O
	 * @throws IllegalArgumentException When the arguments are invalid
	 */
	public static ContainerDocument init(SeekableByteChannel channel, byte[] namespace) throws IOException, IllegalArgumentException, NullPointerException {
		Objects.requireNonNull(channel, "channel cannot be null");
		Objects.requireNonNull(namespace, "namespace cannot be null");
		if (namespace.length > NAMESPACE_SIZE) throw new IllegalArgumentException(String.format("namespace size %d is longer than maximum %d", namespace.length, NAMESPACE_SIZE));

		ByteBuffer buffer = ByteBuffer.allocate(CONTAINER_HEADER_SIZE);
		buffer.order(ByteOrder.LITTLE_ENDIAN);
		buffer.put(SIGNATURE);
		buffer.putLong(0L); // Reserved
		buffer.putLong(0L); // Next chunk
		buffer.put((byte) namespace.length);
		buffer.put(namespace);
		buffer.position(buffer.position() + NAMESPACE_SIZE - namespace.length);
		buffer.flip();
		channel.position(0L);
		ByteChannelUtils.writeFully(channel, buffer);

		return new ContainerDocument(channel, namespace.clone(), new ArrayList<>());
	}

	public static ContainerDocument load(SeekableByteChannel channel) throws IOException {
		Objects.requireNonNull(channel, "channel cannot be null");

		ByteBuffer buffer = ByteBuffer.allocate(CONTAINER_HEADER_SIZE);
		ArrayList<Allocation> allocations = new ArrayList<Allocation>();
		byte[] signature = new byte[SIGNATURE.length];
		byte[] namespace;
		long nextChunkStartOffset;
		long lastChunkEndOffset = CONTAINER_HEADER_SIZE;

		channel.position(0L);
		buffer.clear();
		buffer.order(ByteOrder.LITTLE_ENDIAN);
		ByteChannelUtils.readFully(channel, buffer);
		buffer.flip();
		buffer.get(signature);
		buffer.getLong();
		nextChunkStartOffset = buffer.getLong();
		namespace = new byte[0xFF & buffer.get()];
		buffer.get(namespace);

		if (!Arrays.equals(SIGNATURE, signature)) {
			String found = new String(signature, StandardCharsets.US_ASCII);
			String expected = new String(SIGNATURE, StandardCharsets.US_ASCII);
			throw new IOException(String.format("Signature mismatch (found %s, expected %s)", found, expected));
		}

		while (nextChunkStartOffset != 0L) {
			long chunkOffset;
			int type;
			int size;

			if (nextChunkStartOffset < lastChunkEndOffset) {
				throw new IOException(String.format("next chunk start offset is lower than last chunk end (expecting 0x%016x >= 0x%016x)", nextChunkStartOffset, lastChunkEndOffset));
			}

			if (nextChunkStartOffset != lastChunkEndOffset) {
				long chunkSize = nextChunkStartOffset - lastChunkEndOffset;
				allocations.add(new Allocation(lastChunkEndOffset, chunkSize, null));
			}

			buffer.clear().limit(CHUNK_HEADER_SIZE);
			channel.position(chunkOffset = nextChunkStartOffset);
			ByteChannelUtils.readFully(channel, buffer);
			buffer.flip();
			type = buffer.getInt();
			size = buffer.getInt();
			lastChunkEndOffset = nextChunkStartOffset + CHUNK_HEADER_SIZE + align(size);
			nextChunkStartOffset = buffer.getLong();
			allocations.add(new Allocation(chunkOffset, CHUNK_HEADER_SIZE + align(size), new Data(chunkOffset + CHUNK_HEADER_SIZE, size, type)));
		}

		return new ContainerDocument(channel, namespace, allocations);
	}

	public SeekableByteChannel getChannel() {
		return channel;
	}

	public byte[] getNamespace() {
		return namespace.clone();
	}
	
	public List<Allocation> getAllocations() {
		return Collections.unmodifiableList(allocations);
	}

	public Set<Data> getData() {
		return Collections.unmodifiableSet(dataToAllocation.keySet());
	}

	/**
	 * <p>
	 * Allocate a new chunk in the container.
	 * </p>
	 *
	 * <p>
	 * There can be more than 1 chunk of the same type ID in the document.
	 * </p>
	 *
	 * @param type The type of the chunk
	 * @param size The size of the chunk data in bytes
	 * @return The data containing offset to the start of the chunk
	 * @throws IOException 
	 */
	public Data allocate(int type, int size) throws IOException {
		ByteBuffer buffer = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
		long newChunkSize = CHUNK_HEADER_SIZE + align(size);

		for (int index = 0; index < allocations.size(); index++) {
			Allocation allocation = allocations.get(index);
			if (allocation.data != null) continue;
			if (allocation.size < newChunkSize) continue;

			// Found empty space in the middle of the container
			Data data = new Data(allocation.offset + CHUNK_HEADER_SIZE, size, type);
			Allocation newAllocation = allocation.withSize(newChunkSize).withData(data);
			allocations.set(index, allocation.withOffset(allocation.offset + newChunkSize).withSize(allocation.size - newChunkSize));
			allocations.add(index, newAllocation);

			int prevIndex = indexOfChunk(index, -1);
			int nextIndex = indexOfChunk(index, +1);

			buffer.clear();
			buffer.putLong(newAllocation.offset);
			buffer.flip();
			channel.position((prevIndex != -1 ? allocations.get(prevIndex).offset : SIGNATURE.length) + 8);
			ByteChannelUtils.writeFully(channel, buffer);

			buffer.clear();
			buffer.putInt(type);
			buffer.putInt(size);
			buffer.putLong(nextIndex != -1 ? allocations.get(nextIndex).offset : 0);
			buffer.flip();
			channel.position(newAllocation.offset);
			ByteChannelUtils.writeFully(channel, buffer);

			dataToAllocation.put(data, newAllocation);
			return data;
		}

		// Append to the end
		long chunkOffset = allocations.isEmpty() ? CONTAINER_HEADER_SIZE : allocations.get(allocations.size() - 1).endOffset();
		long pointerOffset = (allocations.isEmpty() ? SIGNATURE.length : allocations.get(allocations.size() - 1).offset) + 8;

		buffer.clear();
		buffer.putInt(type);
		buffer.putInt(size);
		buffer.putLong(0L);
		buffer.flip();
		channel.position(chunkOffset);
		ByteChannelUtils.writeFully(channel, buffer);

		buffer.clear();
		buffer.putLong(chunkOffset);
		buffer.flip();
		channel.position(pointerOffset);
		ByteChannelUtils.writeFully(channel, buffer);

		Data data = new Data(chunkOffset + CHUNK_HEADER_SIZE, size, type);
		Allocation allocation = new Allocation(chunkOffset, newChunkSize, data);
		allocations.add(allocation);
		dataToAllocation.put(data, allocation);
		return data;
	}

	public void delete(Data data) throws IOException {
		Objects.requireNonNull(data, "data cannot be null");

		Allocation allocation = dataToAllocation.get(data);
		int index = allocations.indexOf(allocation);
		if (index == -1) return;

		ByteBuffer buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
		int prevIndex = indexOfChunk(index, -1);
		int nextIndex = indexOfChunk(index, +1);

		buffer.clear();
		buffer.putLong(nextIndex != -1 ? allocations.get(nextIndex).offset : 0L);
		buffer.flip();
		channel.position((prevIndex != -1 ? allocations.get(prevIndex).offset : SIGNATURE.length) + 8);
		ByteChannelUtils.writeFully(channel, buffer);

		Allocation newAllocation = allocation.withData(null);
		allocations.set(index, newAllocation);
		dataToAllocation.remove(data);

		// Forward merge
		if (index != allocations.size() - 1 && allocations.get(index + 1).data == null) {
			long expand = allocations.get(index + 1).size;
			newAllocation = newAllocation.withSize(newAllocation.size + expand);
			allocations.set(index, newAllocation);
			allocations.remove(index + 1);
		}

		// Backward merge
		if (index != 0 && allocations.get(index - 1).data == null) {
			long expand = allocations.get(index - 1).size;
			newAllocation = newAllocation.withOffset(newAllocation.offset - expand).withSize(newAllocation.size + expand);
			allocations.set(index, newAllocation);
			allocations.remove(index - 1);
		}

		// Remove trailing
		while (!allocations.isEmpty() && allocations.get(allocations.size() - 1).data == null) {
			allocations.remove(allocations.size() - 1);
		}
	}

	@Override
	public void close() throws IOException {
		channel.close();
	}

	/**
	 * <p>
	 * Align the value to {@link #ALIGNMENT}. Alignment is used in Nahara's Container v1 to optimize I/O operations.
	 * </p>
	 *
	 * @param v The value to align
	 * @return The aligned value
	 */
	private static int align(int v) {
		return (v / ALIGNMENT + ((v % ALIGNMENT) == 0 ? 0 : 1)) * ALIGNMENT;
	}

	private int indexOfChunk(int start, int direction) {
		if (direction == 0) throw new IllegalArgumentException("Direction is zero");
		direction = Integer.signum(direction);

		for (int i = start + direction; i >= 0 && i < allocations.size(); i += direction) {
			Allocation allocation = allocations.get(i);
			if (allocation.data == null) continue;
			return i;
		}

		return -1;
	}

	/**
	 * <p>
	 * Represent an allocation inside the container.
	 * </p>
	 */
	@NullMarked
	public static final class Allocation {
		public final long offset;
		public final long size;
		public final @Nullable Data data;

		public Allocation(long offset, long size, @Nullable Data data) {
			this.offset = offset;
			this.size = size;
			this.data = data;
		}

		public long endOffset() {
			return offset + size;
		}

		private Allocation withOffset(long offset) {
			return new Allocation(offset, size, data);
		}

		private Allocation withSize(long size) {
			return new Allocation(offset, size, data);
		}

		private Allocation withData(@Nullable Data data) {
			return new Allocation(offset, size, data);
		}

		@Override
		public String toString() {
			return String.format("(0x%x + %d bytes -> %s)", offset, size, data != null ? data : "<unallocated>");
		}
	}

	@NullMarked
	public static final class Data {
		public final long offset;
		public final int size;
		public final int type;

		public Data(long offset, int size, int type) {
			this.offset = offset;
			this.size = size;
			this.type = type;
		}

		@Override
		public String toString() {
			return String.format("%d: 0x%x + %d bytes", type, offset, size);
		}
	}
}
