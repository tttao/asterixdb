/*
 * Copyright 2009-2010 by The Regents of the University of California
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

package edu.uci.ics.hyracks.storage.am.lsm.invertedindex.util;

import edu.uci.ics.hyracks.api.dataflow.value.IBinaryComparatorFactory;
import edu.uci.ics.hyracks.api.dataflow.value.ITypeTraits;
import edu.uci.ics.hyracks.api.exceptions.HyracksDataException;
import edu.uci.ics.hyracks.data.std.accessors.PointableBinaryComparatorFactory;
import edu.uci.ics.hyracks.data.std.primitive.IntegerPointable;
import edu.uci.ics.hyracks.data.std.primitive.UTF8StringPointable;
import edu.uci.ics.hyracks.storage.am.btree.frames.BTreeNSMInteriorFrameFactory;
import edu.uci.ics.hyracks.storage.am.btree.frames.BTreeNSMLeafFrameFactory;
import edu.uci.ics.hyracks.storage.am.btree.impls.BTree;
import edu.uci.ics.hyracks.storage.am.common.api.IFreePageManager;
import edu.uci.ics.hyracks.storage.am.common.api.ITreeIndexFrameFactory;
import edu.uci.ics.hyracks.storage.am.common.api.ITreeIndexMetaDataFrameFactory;
import edu.uci.ics.hyracks.storage.am.common.api.ITreeIndexTupleWriterFactory;
import edu.uci.ics.hyracks.storage.am.common.frames.LIFOMetaDataFrameFactory;
import edu.uci.ics.hyracks.storage.am.common.freepage.LinkedListFreePageManager;
import edu.uci.ics.hyracks.storage.am.common.freepage.LinkedListFreePageManagerFactory;
import edu.uci.ics.hyracks.storage.am.common.tuples.TypeAwareTupleWriterFactory;
import edu.uci.ics.hyracks.storage.am.lsm.invertedindex.LSMInvertedIndexTestHarness;
import edu.uci.ics.hyracks.storage.am.lsm.invertedindex.impls.LSMInvertedIndex;
import edu.uci.ics.hyracks.storage.am.lsm.invertedindex.inmemory.InMemoryInvertedIndex;
import edu.uci.ics.hyracks.storage.am.lsm.invertedindex.ondisk.OnDiskInvertedIndex;
import edu.uci.ics.hyracks.storage.am.lsm.invertedindex.tokenizers.IBinaryTokenizer;
import edu.uci.ics.hyracks.storage.am.lsm.invertedindex.util.InvertedIndexUtils;

public class InvertedIndexTestUtils {
    public static OnDiskInvertedIndex createTestInvertedIndex(LSMInvertedIndexTestHarness harness, IBinaryTokenizer tokenizer)
            throws HyracksDataException {
        ITreeIndexMetaDataFrameFactory metaFrameFactory = new LIFOMetaDataFrameFactory();
        ITypeTraits[] btreeTypeTraits = new ITypeTraits[] { UTF8StringPointable.TYPE_TRAITS,
                IntegerPointable.TYPE_TRAITS, IntegerPointable.TYPE_TRAITS, IntegerPointable.TYPE_TRAITS,
                IntegerPointable.TYPE_TRAITS };
        ITreeIndexTupleWriterFactory tupleWriterFactory = new TypeAwareTupleWriterFactory(btreeTypeTraits);
        ITreeIndexFrameFactory leafFrameFactory = new BTreeNSMLeafFrameFactory(tupleWriterFactory);
        ITreeIndexFrameFactory interiorFrameFactory = new BTreeNSMInteriorFrameFactory(tupleWriterFactory);
        IFreePageManager freePageManager = new LinkedListFreePageManager(harness.getDiskBufferCache(), 0,
                metaFrameFactory);
        IBinaryComparatorFactory[] btreeCmpFactories = new IBinaryComparatorFactory[] { PointableBinaryComparatorFactory
                .of(UTF8StringPointable.FACTORY) };
        return InvertedIndexUtils.createInvertedIndex(harness.getDiskBufferCache(), 
                harness.getInvListTypeTraits(), harness.getInvListCmpFactories(), tokenizer);
    }

    public static InMemoryInvertedIndex createInMemoryInvertedIndex(LSMInvertedIndexTestHarness harness,
            IBinaryTokenizer tokenizer) {
        return InvertedIndexUtils.createInMemoryBTreeInvertedindex(harness.getMemBufferCache(),
                harness.getMemFreePageManager(), harness.getTokenTypeTraits(), harness.getInvListTypeTraits(),
                harness.getTokenCmpFactories(), harness.getInvListCmpFactories(),
                tokenizer);
    }

    public static LSMInvertedIndex createLSMInvertedIndex(LSMInvertedIndexTestHarness harness,
            IBinaryTokenizer tokenizer) {
        return InvertedIndexUtils.createLSMInvertedIndex(harness.getMemBufferCache(),
                harness.getMemFreePageManager(), harness.getTokenTypeTraits(), harness.getInvListTypeTraits(),
                harness.getTokenCmpFactories(), harness.getInvListCmpFactories(),
                tokenizer, harness.getDiskBufferCache(),
                new LinkedListFreePageManagerFactory(harness.getDiskBufferCache(), new LIFOMetaDataFrameFactory()),
                harness.getIOManager(), harness.getOnDiskDir(), harness.getDiskFileMapProvider());
    }
}
