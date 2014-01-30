//
// Copyright (C) 2005-2011 Cleversafe, Inc. All rights reserved.
//
// Contact Information:
// Cleversafe, Inc.
// 222 South Riverside Plaza
// Suite 1700
// Chicago, IL 60606, USA
//
// licensing@cleversafe.com
//
// END-OF-HEADER
//
// -----------------------
// @author: rveitch
//
// Date: Oct 23, 2013
// ---------------------

package com.cleversafe.oom.distribution;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import java.util.Random;

/**
 * A <code>Distribution</code> implementation that returns values conforming to a lognormal
 * distribution.
 */
public class LogNormalDistribution implements Distribution
{
   private final double mean;
   private final double spread;
   private final Random random;

   /**
    * Constructs a <code>LogNormalDistribution</code> instance
    * 
    * @param mean
    *           the mean value of this distribution
    * @param spread
    *           the spread of this distribution
    * @throws IllegalArgumentException
    *            if mean is negative
    * @throws IllegalArgumentException
    *            if spread is negative
    */
   public LogNormalDistribution(final double mean, final double spread)
   {
      this(mean, spread, new Random());
   }

   /**
    * Constructs a <code>LogNormalDistribution</code> instance, using the provided
    * <code>Random</code> instance for random seed data
    * 
    * @param mean
    *           the mean value of this distribution
    * @param spread
    *           the spread of this distribution
    * @throws IllegalArgumentException
    *            if mean is negative
    * @throws IllegalArgumentException
    *            if spread is negative
    * @throws NullPointerException
    *            if random is null
    */
   public LogNormalDistribution(final double mean, final double spread, final Random random)
   {
      checkArgument(mean >= 0.0, "mean must be >= 0.0 [%s]", mean);
      checkArgument(spread >= 0.0, "spread must be >= 0.0 [%s]", spread);
      checkNotNull(random, "random must not be null");
      this.mean = mean;
      this.spread = spread;
      this.random = random;
   }

   @Override
   public double getMean()
   {
      return this.mean;
   }

   @Override
   public double getSpread()
   {
      return this.spread;
   }

   /**
    * {@inheritDoc}
    * 
    * This implementation will always return a non-negative sample.
    */
   @Override
   public double nextSample()
   {
      double result;
      final double ratioSquared = (this.spread * this.spread) / (this.mean * this.mean);
      final double logRatio = Math.log(ratioSquared + 1.0);
      final double normalMean = Math.log(this.mean) - 0.5 * logRatio;
      final double normalSD = Math.sqrt(logRatio);
      do
      {
         result = Math.exp(normalMean + normalSD * this.random.nextGaussian());
      } while (result < 0);
      return result;
   }
}
