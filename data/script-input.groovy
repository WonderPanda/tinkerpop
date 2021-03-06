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
def parse(line, factory) {
    // "factory" should no longer be used, as ScriptElementFactory is now deprecated
    // instead use the global "graph" variable which is the local star graph for the current element
    def parts = line.split(/ /)
    def (id, label, name, x) = parts[0].split(/:/).toList()
    def v1 = graph.addVertex(T.id, id, T.label, label)
    if (name != null) v1.property("name", name) // first value is always the name
    if (x != null) {
        // second value depends on the vertex label; it's either
        // the age of a person or the language of a project
        if (label.equals("project")) v1.property("lang", x)
        else v1.property("age", Integer.valueOf(x))
    }
    if (parts.length == 2) {
        parts[1].split(/,/).grep { !it.isEmpty() }.each {
            def (eLabel, refId, weight) = it.split(/:/).toList()
            def v2 = graph.addVertex(T.id, refId)
            v1.addOutEdge(eLabel, v2, "weight", Double.valueOf(weight))
        }
    }
    return v1
}
