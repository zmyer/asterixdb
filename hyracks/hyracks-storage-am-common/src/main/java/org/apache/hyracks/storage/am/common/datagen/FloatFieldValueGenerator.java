/*
 * Copyright 2009-2013 by The Regents of the University of California
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * you may obtain a copy of the License from
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hyracks.storage.am.common.datagen;

import java.util.Random;

public class FloatFieldValueGenerator implements IFieldValueGenerator<Float> {
    protected final Random rnd;

    public FloatFieldValueGenerator(Random rnd) {
        this.rnd = rnd;
    }

    @Override
    public Float next() {
        return rnd.nextFloat();
    }

    @Override
    public void reset() {
    }
}