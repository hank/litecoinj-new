/*
 * Copyright 2013 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.bitcoin.params;

import com.google.bitcoin.core.NetworkParameters;
import com.google.bitcoin.core.Utils;

import static com.google.common.base.Preconditions.checkState;

/**
 * Parameters for the old version 2 testnet. This is not useful to you - it exists only because some unit tests are
 * based on it.
 */
public class TestNet2Params extends NetworkParameters {
    public TestNet2Params() {
        super();
        id = ID_TESTNET;
        packetMagic = 0xfcc1b7dc;
        port = 19333;
        addressHeader = 111;
        interval = INTERVAL;
        targetTimespan = TARGET_TIMESPAN;
        proofOfWorkLimit = Utils.decodeCompactBits(0x1d0fffffL);
        acceptableAddressCodes = new int[] { 111 };
        dumpedPrivateKeyHeader = 239;
        genesisBlock.setTime(1320884152L);
        genesisBlock.setDifficultyTarget(0x1d018ea7L);
        genesisBlock.setNonce(3562614017L);
        spendableCoinbaseDepth = 100;
        subsidyDecreaseBlockCount = 840000;
        String genesisHash = genesisBlock.getHashAsString();
        LOGGER.info("Genesis Hash: " + genesisHash.toString());
        checkState(genesisHash.equals("54477b4910d7f39fb05db75ece889d1fd690c4357b00268a54e7239f757b5d6c"));
        dnsSeeds = null;
    }

    private static TestNet2Params instance;
    public static synchronized TestNet2Params get() {
        if (instance == null) {
            instance = new TestNet2Params();
        }
        return instance;
    }
}
