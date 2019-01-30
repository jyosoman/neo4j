/*
 *   Copyright (c) 2018.
 *   This file is part of NeGraph.
 *
 *  NeGraph is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  NeGraph is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with NeGraph.  If not, see <https://www.gnu.org/licenses/>.
 * @author Jyothish Soman, cl cam uk
 */

package org.cam.storage.levelgraph.transaction;

import org.cam.storage.levelgraph.Pair;
import org.cam.storage.levelgraph.storage.IdGenerator;

public class TransactionIdProvider {
    private IdGenerator idGenerator;
    private long left, right;

    TransactionIdProvider(IdGenerator idGenerator1) {
        idGenerator = idGenerator1;
        getPair();
    }

    private void getPair() {
        Pair<Long, Long> idrange = idGenerator.getIdRange();
        left = idrange.getFirst();
        right = idrange.getSecond();
    }

    public Long getId() {
        if (left == right) {
            getPair();
        }
        return left++;
    }

}