/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.tinkerpop.gremlin.process.traversal.step.filter;

import org.apache.tinkerpop.gremlin.process.computer.MemoryComputeKey;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.Traverser;
import org.apache.tinkerpop.gremlin.process.traversal.step.Bypassing;
import org.apache.tinkerpop.gremlin.process.traversal.step.GraphComputing;
import org.apache.tinkerpop.gremlin.process.traversal.step.Ranging;
import org.apache.tinkerpop.gremlin.process.traversal.traverser.TraverserRequirement;
import org.apache.tinkerpop.gremlin.process.traversal.traverser.util.TraverserSet;
import org.apache.tinkerpop.gremlin.process.traversal.util.FastNoSuchElementException;
import org.apache.tinkerpop.gremlin.structure.util.StringFactory;

import java.io.Serializable;
import java.util.Collections;
import java.util.Iterator;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BinaryOperator;

/**
 * @author Bob Briody (http://bobbriody.com)
 * @author Marko A. Rodriguez (http://markorodriguez.com)
 */
public final class RangeGlobalStep<S> extends FilterStep<S> implements Ranging, GraphComputing<TraverserSet<S>>, Bypassing {

    private long low;
    private final long high;
    private AtomicLong counter = new AtomicLong(0l);
    private boolean bypass;

    public RangeGlobalStep(final Traversal.Admin traversal, final long low, final long high) {
        super(traversal);
        if (low != -1 && high != -1 && low > high) {
            throw new IllegalArgumentException("Not a legal range: [" + low + ", " + high + ']');
        }
        this.low = low;
        this.high = high;
    }

    @Override
    protected boolean filter(final Traverser.Admin<S> traverser) {
        if (this.bypass) return true;

        if (this.high != -1 && this.counter.get() >= this.high) {
            throw FastNoSuchElementException.instance();
        }

        long avail = traverser.bulk();
        if (this.counter.get() + avail <= this.low) {
            // Will not surpass the low w/ this traverser. Skip and filter the whole thing.
            this.counter.getAndAdd(avail);
            return false;
        }

        // Skip for the low and trim for the high. Both can happen at once.

        long toSkip = 0;
        if (this.counter.get() < this.low) {
            toSkip = this.low - this.counter.get();
        }

        long toTrim = 0;
        if (this.high != -1 && this.counter.get() + avail >= this.high) {
            toTrim = this.counter.get() + avail - this.high;
        }

        long toEmit = avail - toSkip - toTrim;
        this.counter.getAndAdd(toSkip + toEmit);
        traverser.asAdmin().setBulk(toEmit);

        return true;
    }

    @Override
    public void reset() {
        super.reset();
        this.counter.set(0l);
    }

    @Override
    public String toString() {
        return StringFactory.stepString(this, this.low, this.high);
    }

    @Override
    public long getLowRange() {
        return this.low;
    }

    @Override
    public long getHighRange() {
        return this.high;
    }

    @Override
    public RangeGlobalStep<S> clone() {
        final RangeGlobalStep<S> clone = (RangeGlobalStep<S>) super.clone();
        clone.counter = new AtomicLong(0l);
        return clone;
    }

    @Override
    public int hashCode() {
        return super.hashCode() ^ Long.hashCode(this.low) ^ Long.hashCode(this.high);
    }

    @Override
    public Set<TraverserRequirement> getRequirements() {
        return Collections.singleton(TraverserRequirement.BULK);
    }

    @Override
    public void onGraphComputer() {

    }

    @Override
    public Optional<MemoryComputeKey> getMemoryComputeKey() {
        return Optional.of(MemoryComputeKey.of(this.getId(), new RangeBiOperator(this.high), false, true));
    }

    @Override
    public TraverserSet<S> generateFinalResult(final TraverserSet<S> traverserSet) {
        final TraverserSet<S> resultSet = new TraverserSet<>();
        this.addStarts((Iterator) traverserSet.iterator());
        this.forEachRemaining(t -> resultSet.add(t.asAdmin()));
        return resultSet;
    }

    @Override
    public void setBypass(final boolean bypass) {
        this.bypass = bypass;
    }

    ////////////////

    public static final class RangeBiOperator implements BinaryOperator<TraverserSet>, Serializable {

        private final long highRange;

        public RangeBiOperator(final long highRange) {
            this.highRange = highRange;
        }

        @Override
        public TraverserSet apply(final TraverserSet mutatingSeed, final TraverserSet set) {
            if (mutatingSeed.size() < this.highRange)
                mutatingSeed.addAll(set);
            return mutatingSeed;
        }
    }
}
