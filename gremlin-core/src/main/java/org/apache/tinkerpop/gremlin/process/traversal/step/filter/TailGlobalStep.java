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
import org.apache.tinkerpop.gremlin.process.traversal.step.util.AbstractStep;
import org.apache.tinkerpop.gremlin.process.traversal.traverser.TraverserRequirement;
import org.apache.tinkerpop.gremlin.process.traversal.traverser.util.TraverserSet;
import org.apache.tinkerpop.gremlin.structure.util.StringFactory;

import java.io.Serializable;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.Optional;
import java.util.Set;
import java.util.function.BinaryOperator;

/**
 * @author Matt Frantz (http://github.com/mhfrantz)
 */
public final class TailGlobalStep<S> extends AbstractStep<S, S> implements Bypassing, GraphComputing<TraverserSet<S>> {

    private final long limit;
    private Deque<Traverser.Admin<S>> tail;
    private long tailBulk = 0L;
    private boolean bypass = false;

    public TailGlobalStep(final Traversal.Admin traversal, final long limit) {
        super(traversal);
        this.limit = limit;
        this.tail = new ArrayDeque<>((int) limit);
    }

    public void setBypass(final boolean bypass) {
        this.bypass = bypass;
    }

    @Override
    public Traverser<S> processNextStart() {
        if (this.bypass) {
            // If we are bypassing this step, let everything through.
            return this.starts.next();
        } else {
            // Pull everything available before we start delivering from the tail buffer.
            if (this.starts.hasNext()) {
                this.starts.forEachRemaining(this::addTail);
            }
            // Pull the oldest traverser from the tail buffer.
            final Traverser.Admin<S> oldest = this.tail.pop();
            // Trim any excess from the oldest traverser.
            final long excess = this.tailBulk - this.limit;
            if (excess > 0)
                oldest.setBulk(oldest.bulk() - excess);
            // Account for the loss of bulk in the tail buffer as we emit the oldest traverser.
            this.tailBulk -= oldest.bulk();
            return oldest;
        }
    }

    @Override
    public void reset() {
        super.reset();
        this.tail.clear();
        this.tailBulk = 0L;
    }

    @Override
    public String toString() {
        return StringFactory.stepString(this, this.limit);
    }

    @Override
    public TailGlobalStep<S> clone() {
        final TailGlobalStep<S> clone = (TailGlobalStep<S>) super.clone();
        clone.tail = new ArrayDeque<>((int) this.limit);
        clone.tailBulk = 0L;
        return clone;
    }

    @Override
    public int hashCode() {
        return super.hashCode() ^ Long.hashCode(this.limit);
    }

    @Override
    public Set<TraverserRequirement> getRequirements() {
        return Collections.singleton(TraverserRequirement.BULK);
    }

    private void addTail(Traverser.Admin<S> start) {
        // Calculate the tail bulk including this new start.
        this.tailBulk += start.bulk();
        // Evict from the tail buffer until we have enough room.
        while (!this.tail.isEmpty()) {
            final Traverser.Admin<S> oldest = this.tail.getFirst();
            final long bulk = oldest.bulk();
            if (this.tailBulk - bulk < limit)
                break;
            this.tail.pop();
            this.tailBulk -= bulk;
        }
        this.tail.add(start);
    }

    @Override
    public void onGraphComputer() {

    }

    @Override
    public Optional<MemoryComputeKey> getMemoryComputeKey() {
        return Optional.of(MemoryComputeKey.of(this.getId(), new TailBiOperator(this.limit), false, true));
    }

    @Override
    public TraverserSet<S> generateFinalResult(final TraverserSet<S> traverserSet) {
        final TraverserSet<S> resultSet = new TraverserSet<>();
        this.addStarts((Iterator) traverserSet.iterator());
        this.forEachRemaining(t -> resultSet.add(t.asAdmin()));
        return resultSet;
    }

    /////////////////////

    public static final class TailBiOperator implements BinaryOperator<TraverserSet>, Serializable {

        private final long limit;

        public TailBiOperator(final long limit) {
            this.limit = limit;
        }

        @Override
        public TraverserSet apply(final TraverserSet mutatingSeed, final TraverserSet set) {
            if (mutatingSeed.size() < this.limit)
                mutatingSeed.addAll(set);
            return mutatingSeed;
        }
    }
}
