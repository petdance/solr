/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.store.blockcache;

import java.io.EOFException;
import java.io.IOException;

import org.apache.lucene.store.BufferedIndexInput;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.IndexOutput;

/**
 * @lucene.experimental
 */
public abstract class CustomBufferedIndexInput extends IndexInput {
  
  public static final int BUFFER_SIZE = Integer.getInteger("solr.hdfs.readbuffer.size.default", 32768);
  
  private int bufferSize = BUFFER_SIZE;
  
  protected byte[] buffer;
  
  private long bufferStart = 0; // position in file of buffer
  private int bufferLength = 0; // end of valid bytes
  private int bufferPosition = 0; // next byte to read
  
  private final Store store;
  
  @Override
  public byte readByte() throws IOException {
    if (bufferPosition >= bufferLength) refill();
    return buffer[bufferPosition++];
  }
  
  public CustomBufferedIndexInput(String resourceDesc) {
    this(resourceDesc, BUFFER_SIZE);
  }
  
  public CustomBufferedIndexInput(String resourceDesc, int bufferSize) {
    super(resourceDesc);
    checkBufferSize(bufferSize);
    this.bufferSize = bufferSize;
    this.store = BufferStore.instance(bufferSize);
  }
  
  private void checkBufferSize(int bufferSize) {
    if (bufferSize <= 0) throw new IllegalArgumentException(
        "bufferSize must be greater than 0 (got " + bufferSize + ")");
  }
  
  @Override
  public void readBytes(byte[] b, int offset, int len) throws IOException {
    readBytes(b, offset, len, true);
  }
  
  @Override
  public void readBytes(byte[] b, int offset, int len, boolean useBuffer)
      throws IOException {
    
    if (len <= (bufferLength - bufferPosition)) {
      // the buffer contains enough data to satisfy this request
      if (len > 0) // to allow b to be null if len is 0...
      System.arraycopy(buffer, bufferPosition, b, offset, len);
      bufferPosition += len;
    } else {
      // the buffer does not have enough data. First serve all we've got.
      int available = bufferLength - bufferPosition;
      if (available > 0) {
        System.arraycopy(buffer, bufferPosition, b, offset, available);
        offset += available;
        len -= available;
        bufferPosition += available;
      }
      // and now, read the remaining 'len' bytes:
      if (useBuffer && len < bufferSize) {
        // If the amount left to read is small enough, and
        // we are allowed to use our buffer, do it in the usual
        // buffered way: fill the buffer and copy from it:
        refill();
        if (bufferLength < len) {
          // Throw an exception when refill() could not read len bytes:
          System.arraycopy(buffer, 0, b, offset, bufferLength);
          throw new EOFException("read past EOF: " + this);
        } else {
          System.arraycopy(buffer, 0, b, offset, len);
          bufferPosition = len;
        }
      } else {
        // The amount left to read is larger than the buffer
        // or we've been asked to not use our buffer -
        // there's no performance reason not to read it all
        // at once. Note that unlike the previous code of
        // this function, there is no need to do a seek
        // here, because there's no need to reread what we
        // had in the buffer.
        long after = bufferStart + bufferPosition + len;
        if (after > length()) throw new EOFException("read past EOF: " + this);
        readInternal(b, offset, len);
        bufferStart = after;
        bufferPosition = 0;
        bufferLength = 0; // trigger refill() on read
      }
    }
  }

  @Override
  public short readShort() throws IOException {
    if (2 <= (bufferLength - bufferPosition)) {
      final byte b1 = buffer[bufferPosition++];
      final byte b2 = buffer[bufferPosition++];
      return (short) ((b2 & 0xFF) << 8 | (b1 & 0xFF));
    } else {
      return super.readShort();
    }
  }

  @Override
  public int readInt() throws IOException {
    if (4 <= (bufferLength - bufferPosition)) {
      final byte b1 = buffer[bufferPosition++];
      final byte b2 = buffer[bufferPosition++];
      final byte b3 = buffer[bufferPosition++];
      final byte b4 = buffer[bufferPosition++];
      return (b4 & 0xFF) << 24 | (b3 & 0xFF) << 16 | (b2 & 0xFF) << 8 | (b1 & 0xFF);
    } else {
      return super.readInt();
    }
  }
  
  @Override
  public long readLong() throws IOException {
    if (8 <= (bufferLength - bufferPosition)) {
      return (readInt() & 0xFFFFFFFFL) | (((long) readInt()) << 32);
    } else {
      return super.readLong();
    }
  }
  
  @Override
  public int readVInt() throws IOException {
    if (5 <= (bufferLength - bufferPosition)) {
      byte b = buffer[bufferPosition++];
      if (b >= 0) return b;
      int i = b & 0x7F;
      b = buffer[bufferPosition++];
      i |= (b & 0x7F) << 7;
      if (b >= 0) return i;
      b = buffer[bufferPosition++];
      i |= (b & 0x7F) << 14;
      if (b >= 0) return i;
      b = buffer[bufferPosition++];
      i |= (b & 0x7F) << 21;
      if (b >= 0) return i;
      b = buffer[bufferPosition++];
      // Warning: the next ands use 0x0F / 0xF0 - beware copy/paste errors:
      i |= (b & 0x0F) << 28;
      if ((b & 0xF0) == 0) return i;
      throw new RuntimeException("Invalid vInt detected (too many bits)");
    } else {
      return super.readVInt();
    }
  }
  
  @Override
  public long readVLong() throws IOException {
    if (9 <= bufferLength - bufferPosition) {
      byte b =buffer[bufferPosition++];
      if (b >= 0) return b;
      long i = b & 0x7FL;
      b = buffer[bufferPosition++];
      i |= (b & 0x7FL) << 7;
      if (b >= 0) return i;
      b = buffer[bufferPosition++];
      i |= (b & 0x7FL) << 14;
      if (b >= 0) return i;
      b = buffer[bufferPosition++];
      i |= (b & 0x7FL) << 21;
      if (b >= 0) return i;
      b = buffer[bufferPosition++];
      i |= (b & 0x7FL) << 28;
      if (b >= 0) return i;
      b = buffer[bufferPosition++];
      i |= (b & 0x7FL) << 35;
      if (b >= 0) return i;
      b = buffer[bufferPosition++];
      i |= (b & 0x7FL) << 42;
      if (b >= 0) return i;
      b = buffer[bufferPosition++];
      i |= (b & 0x7FL) << 49;
      if (b >= 0) return i;
      b = buffer[bufferPosition++];
      i |= (b & 0x7FL) << 56;
      if (b >= 0) return i;
      throw new RuntimeException("Invalid vLong detected (negative values disallowed)");
    } else {
      return super.readVLong();
    }
  }
  
  private void refill() throws IOException {
    long start = bufferStart + bufferPosition;
    long end = start + bufferSize;
    if (end > length()) // don't read past EOF
    end = length();
    int newLength = (int) (end - start);
    if (newLength <= 0) throw new EOFException("read past EOF: " + this);
    
    if (buffer == null) {
      buffer = store.takeBuffer(bufferSize);
      seekInternal(bufferStart);
    }
    readInternal(buffer, 0, newLength);
    bufferLength = newLength;
    bufferStart = start;
    bufferPosition = 0;
  }
  
  @Override
  public final void close() throws IOException {
    closeInternal();
    store.putBuffer(buffer);
    buffer = null;
  }
  
  protected abstract void closeInternal() throws IOException;
  
  /**
   * Expert: implements buffer refill. Reads bytes from the current position in
   * the input.
   * 
   * @param b
   *          the array to read bytes into
   * @param offset
   *          the offset in the array to start storing bytes
   * @param length
   *          the number of bytes to read
   */
  protected abstract void readInternal(byte[] b, int offset, int length)
      throws IOException;
  
  @Override
  public long getFilePointer() {
    return bufferStart + bufferPosition;
  }
  
  @Override
  public void seek(long pos) throws IOException {
    if (pos >= bufferStart && pos < (bufferStart + bufferLength)) bufferPosition = (int) (pos - bufferStart); // seek
                                                                                                              // within
                                                                                                              // buffer
    else {
      bufferStart = pos;
      bufferPosition = 0;
      bufferLength = 0; // trigger refill() on read()
      seekInternal(pos);
    }
  }
  
  /**
   * Expert: implements seek. Sets current position in this file, where the next
   * {@link #readInternal(byte[],int,int)} will occur.
   * 
   * @see #readInternal(byte[],int,int)
   */
  protected abstract void seekInternal(long pos) throws IOException;
  
  @Override
  public IndexInput clone() {
    CustomBufferedIndexInput clone = (CustomBufferedIndexInput) super.clone();
    
    clone.buffer = null;
    clone.bufferLength = 0;
    clone.bufferPosition = 0;
    clone.bufferStart = getFilePointer();
    
    return clone;
  }
  
  @Override
  public IndexInput slice(String sliceDescription, long offset, long length) throws IOException {
    return BufferedIndexInput.wrap(sliceDescription, this, offset, length);
  }

  /**
   * Flushes the in-memory bufer to the given output, copying at most
   * <code>numBytes</code>.
   * <p>
   * <b>NOTE:</b> this method does not refill the buffer, however it does
   * advance the buffer position.
   * 
   * @return the number of bytes actually flushed from the in-memory buffer.
   */
  protected int flushBuffer(IndexOutput out, long numBytes) throws IOException {
    int toCopy = bufferLength - bufferPosition;
    if (toCopy > numBytes) {
      toCopy = (int) numBytes;
    }
    if (toCopy > 0) {
      out.writeBytes(buffer, bufferPosition, toCopy);
      bufferPosition += toCopy;
    }
    return toCopy;
  }
}
