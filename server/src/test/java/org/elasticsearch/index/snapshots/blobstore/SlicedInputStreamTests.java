/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */
package org.elasticsearch.index.snapshots.blobstore;

import com.carrotsearch.randomizedtesting.generators.RandomNumbers;

import org.elasticsearch.test.ESTestCase;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Random;

import static org.hamcrest.Matchers.equalTo;

public class SlicedInputStreamTests extends ESTestCase {
    public void testReadRandom() throws IOException {
        int parts = randomIntBetween(1, 20);
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        int numWriteOps = scaledRandomIntBetween(1000, 10000);
        final long seed = randomLong();
        Random random = new Random(seed);
        for (int i = 0; i < numWriteOps; i++) {
            switch (random.nextInt(5)) {
                case 1 -> stream.write(random.nextInt(Byte.MAX_VALUE));
                default -> stream.write(randomBytes(random));
            }
        }

        final CheckClosedInputStream[] streams = new CheckClosedInputStream[parts];
        byte[] bytes = stream.toByteArray();
        int slice = bytes.length / parts;
        int offset = 0;
        int length;
        for (int i = 0; i < parts; i++) {
            length = i == parts - 1 ? bytes.length - offset : slice;
            streams[i] = new CheckClosedInputStream(new ByteArrayInputStream(bytes, offset, length));
            offset += length;
        }

        SlicedInputStream input = new SlicedInputStream(parts) {
            @Override
            protected InputStream openSlice(int slice) throws IOException {
                return streams[slice];
            }
        };
        random = new Random(seed);
        assertThat(input.available(), equalTo(streams[0].available()));
        for (int i = 0; i < numWriteOps; i++) {
            switch (random.nextInt(5)) {
                case 1 -> assertThat(random.nextInt(Byte.MAX_VALUE), equalTo(input.read()));
                default -> {
                    byte[] b = randomBytes(random);
                    byte[] buffer = new byte[b.length];
                    int read = readFully(input, buffer);
                    assertThat(b.length, equalTo(read));
                    assertArrayEquals(b, buffer);
                }
            }
        }

        assertThat(input.available(), equalTo(0));
        for (int i = 0; i < streams.length - 1; i++) {
            assertTrue(streams[i].closed);
        }
        input.close();

        for (int i = 0; i < streams.length; i++) {
            assertTrue(streams[i].closed);
        }
    }

    public void testRandomMarkReset() throws IOException {
        final int slices = randomIntBetween(1, 20);
        final var bytes = randomByteArrayOfLength(randomIntBetween(1000, 10000));
        final int sliceSize = bytes.length / slices;

        final var streamsOpened = new ArrayList<CheckClosedInputStream>();
        SlicedInputStream input = new SlicedInputStream(slices) {
            @Override
            protected InputStream openSlice(int slice) throws IOException {
                final int sliceOffset = slice * sliceSize;
                final int length = slice == slices - 1 ? bytes.length - sliceOffset : sliceSize;
                final var stream = new CheckClosedInputStream(new ByteArrayInputStream(bytes, sliceOffset, length));
                streamsOpened.add(stream);
                return stream;
            }
        };

        // Read up to a random point
        final int mark = randomIntBetween(0, bytes.length);
        if (mark > 0) {
            final var bytesReadUntilMark = new byte[mark];
            input.readNBytes(bytesReadUntilMark, 0, mark);
            final var expectedBytesUntilMark = new ByteArrayInputStream(bytes, 0, mark).readAllBytes();
            assertArrayEquals(expectedBytesUntilMark, bytesReadUntilMark);
        }

        // Reset should throw since there is no mark
        expectThrows(IOException.class, input::reset);

        // Mark
        input.mark(randomNonNegativeInt());

        // Read up to another random point
        final int moreBytes = randomIntBetween(0, bytes.length - mark);
        if (moreBytes > 0) {
            final var moreBytesRead = new byte[moreBytes];
            input.readNBytes(moreBytesRead, 0, moreBytes);
            final var expectedMoreBytes = new ByteArrayInputStream(bytes, mark, moreBytes).readAllBytes();
            assertArrayEquals(expectedMoreBytes, moreBytesRead);
        }

        // Reset
        input.reset();

        // Read all remaining bytes, which should be the bytes from mark up to the end
        final int remainingBytes = bytes.length - mark;
        if (remainingBytes > 0) {
            final var remainingBytesRead = new byte[remainingBytes];
            input.readNBytes(remainingBytesRead, 0, remainingBytes);
            final var expectedRemainingBytes = new ByteArrayInputStream(bytes, mark, remainingBytes).readAllBytes();
            assertArrayEquals(expectedRemainingBytes, remainingBytesRead);
        }

        // Confirm we reached the end and close the stream
        assertThat(input.read(), equalTo(-1));
        input.close();
        streamsOpened.forEach(stream -> assertTrue(stream.closed));
    }

    public void testMarkResetClosedStream() throws IOException {
        final int slices = randomIntBetween(1, 20);
        SlicedInputStream input = new SlicedInputStream(slices) {
            @Override
            protected InputStream openSlice(int slice) throws IOException {
                return new ByteArrayInputStream(new byte[] { 0 }, 0, 1);
            }
        };

        input.skip(randomIntBetween(1, slices));
        input.mark(randomNonNegativeInt());
        input.close();
        // SlicedInputStream supports reading -1 after close without throwing
        assertThat(input.read(), equalTo(-1));
        expectThrows(IOException.class, input::reset);
        assertThat(input.read(), equalTo(-1));
        input.mark(randomNonNegativeInt());
        assertThat(input.read(), equalTo(-1));
    }

    public void testMarkResetUnsupportedStream() throws IOException {
        final int slices = randomIntBetween(1, 20);
        SlicedInputStream input = new SlicedInputStream(slices) {
            @Override
            protected InputStream openSlice(int slice) throws IOException {
                return new ByteArrayInputStream(new byte[] { 0 }, 0, 1);
            }

            @Override
            public boolean markSupported() {
                return false;
            }
        };

        input.mark(randomNonNegativeInt());
        expectThrows(IOException.class, input::reset);
        input.close();
    }

    public void testMarkResetZeroSlices() throws IOException {
        SlicedInputStream input = new SlicedInputStream(0) {
            @Override
            protected InputStream openSlice(int slice) throws IOException {
                throw new AssertionError("should not be called");
            }
        };

        if (randomBoolean()) {
            // randomly initialize the stream
            assertThat(input.read(), equalTo(-1));
        }

        input.mark(randomNonNegativeInt());
        input.reset();
        assertThat(input.read(), equalTo(-1));
        input.close();
    }

    private int readFully(InputStream stream, byte[] buffer) throws IOException {
        for (int i = 0; i < buffer.length;) {
            int read = stream.read(buffer, i, buffer.length - i);
            if (read == -1) {
                if (i == 0) {
                    return -1;
                } else {
                    return i;
                }
            }
            i += read;
        }
        return buffer.length;
    }

    private byte[] randomBytes(Random random) {
        int length = RandomNumbers.randomIntBetween(random, 1, 10);
        byte[] data = new byte[length];
        random.nextBytes(data);
        return data;
    }

    private static final class CheckClosedInputStream extends FilterInputStream {

        public boolean closed = false;

        CheckClosedInputStream(InputStream in) {
            super(in);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }
}
