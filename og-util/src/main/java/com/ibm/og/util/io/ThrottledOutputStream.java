/* Copyright (c) IBM Corporation 2016. All Rights Reserved.
 * Project name: Object Generator
 * This project is licensed under the Apache License 2.0, see LICENSE.
 */

package com.ibm.og.util.io;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import com.google.common.util.concurrent.RateLimiter;

/**
 * An output stream with a configurable maximum throughput
 * 
 * @since 1.0
 */
public class ThrottledOutputStream extends FilterOutputStream {
  private final RateLimiter rateLimiter;

  /**
   * Constructs an output stream with a maximum throughput
   * 
   * @param out the backing output stream to write to
   * @param bytesPerSecond the maximum rate at which this output stream can write
   * @throws IllegalArgumentException if bytesPerSecond is negative or zero
   */
  public ThrottledOutputStream(final OutputStream out, final long bytesPerSecond) {
    super(checkNotNull(out));
    checkArgument(bytesPerSecond > 0, "bytesPerSecond must be > 0 [%s]", bytesPerSecond);
    this.rateLimiter = RateLimiter.create(bytesPerSecond);
  }

  @Override
  public void write(final int b) throws IOException {
    super.write(b);
    throttle(1);
  }

  @Override
  public void write(final byte[] b) throws IOException {
    this.write(b, 0, b.length);
  }

  @Override
  public void write(final byte[] b, final int off, final int len) throws IOException {
    // out.write rather than super.write, FilterOutputStream.write calls write(int b) in loop
    this.out.write(b, off, len);
    throttle(len);
  }

  private void throttle(final int bytes) {
    if (bytes == 1) {
      this.rateLimiter.acquire();
    } else if (bytes > 1) {
      // acquire blocks based on previously acquired permits. If multiple bytes written, call
      // acquire twice so throttling occurs even if write is only called once (small files)
      this.rateLimiter.acquire(bytes - 1);
      this.rateLimiter.acquire();
    }
  }

  @Override
  public String toString() {
    return String.format("ThrottledOutputStream [out=%s, bytesPerSecond=%s]", this.out,
        this.rateLimiter.getRate());
  }
}
