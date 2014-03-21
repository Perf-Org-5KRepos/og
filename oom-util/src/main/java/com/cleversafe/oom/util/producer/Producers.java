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
// Date: Mar 21, 2014
// ---------------------

package com.cleversafe.oom.util.producer;

import com.cleversafe.oom.api.Producer;
import com.cleversafe.oom.util.WeightedRandomChoice;

public class Producers
{
   private Producers()
   {}

   public static <T> Producer<T> of(final T item)
   {
      return ConstantProducer.of(item);
   }

   public static <T> Producer<T> of(final WeightedRandomChoice<T> item)
   {
      return WeightedRandomChoiceProducer.of(item);
   }
}
