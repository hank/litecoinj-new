/**
 * Copyright 2011 Google Inc.
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

package com.google.litecoin.core;

import com.google.litecoin.core.Wallet.BalanceType;
import com.google.litecoin.params.MainNetParams;
import com.google.litecoin.params.TestNet2Params;
import com.google.litecoin.params.UnitTestParams;
import com.google.litecoin.store.BlockStore;
import com.google.litecoin.store.MemoryBlockStore;
import com.google.litecoin.utils.BriefLogFormatter;
import com.google.litecoin.utils.TestUtils;
import com.google.common.util.concurrent.ListenableFuture;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.math.BigInteger;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.logging.Logger;

import static com.google.litecoin.utils.TestUtils.createFakeBlock;
import static com.google.litecoin.utils.TestUtils.createFakeTx;
import static org.junit.Assert.*;

// Handling of chain splits/reorgs are in ChainSplitTests.

public class BlockChainTest {
    private BlockChain testNetChain;
    private BlockChain mainNetChain;

    private Wallet wallet;
    private BlockChain chain;
    private BlockStore blockStore;
    private Address coinbaseTo;
    private NetworkParameters unitTestParams;
    private final StoredBlock[] block = new StoredBlock[1];
    private Transaction coinbaseTransaction;

    private static class TweakableTestNet2Params extends TestNet2Params {
        public void setProofOfWorkLimit(BigInteger limit) {
            proofOfWorkLimit = limit;
        }
    }
    private static final TweakableTestNet2Params testNet = new TweakableTestNet2Params();

    private static final MainNetParams mainNet = new MainNetParams();


    private void resetBlockStore() {
        blockStore = new MemoryBlockStore(unitTestParams);
    }

    @Before
    public void setUp() throws Exception {
        BriefLogFormatter.initVerbose();
        testNetChain = new BlockChain(testNet, new Wallet(testNet), new MemoryBlockStore(testNet));
        mainNetChain = new BlockChain(mainNet, new Wallet(mainNet), new MemoryBlockStore(mainNet));
        Wallet.SendRequest.DEFAULT_FEE_PER_KB = BigInteger.ZERO;

        unitTestParams = UnitTestParams.get();
        wallet = new Wallet(unitTestParams) {
            @Override
            public void receiveFromBlock(Transaction tx, StoredBlock block, BlockChain.NewBlockType blockType,
                                         int relativityOffset) throws VerificationException {
                super.receiveFromBlock(tx, block, blockType, relativityOffset);
                BlockChainTest.this.block[0] = block;
                if (tx.isCoinBase()) {
                    BlockChainTest.this.coinbaseTransaction = tx;
                }
            }
        };
        wallet.addKey(new ECKey());

        resetBlockStore();
        chain = new BlockChain(unitTestParams, wallet, blockStore);

        coinbaseTo = wallet.getKeys().get(0).toAddress(unitTestParams);
    }

    @After
    public void tearDown() {
        Wallet.SendRequest.DEFAULT_FEE_PER_KB = Transaction.REFERENCE_DEFAULT_MIN_TX_FEE;
    }

    @Test
    public void testBasicChaining() throws Exception {
        // Check that we can plug a few blocks together and the futures work.
        ListenableFuture<StoredBlock> future = mainNetChain.getHeightFuture(2);
        // Block 1 from the testnet.
        Block b1 = getBlock1();
        assertTrue(mainNetChain.add(b1));
        assertFalse(future.isDone());
        // Block 2 from the testnet.
        Block b2 = getBlock2();

        // Let's try adding an invalid block.
        long n = b2.getNonce();
        try {
            b2.setNonce(12345L);
            mainNetChain.add(b2);
            fail();
        } catch (VerificationException e) {
            b2.setNonce(n);
        }

        // Now it works because we reset the nonce.
        assertTrue(mainNetChain.add(b2));
        assertTrue(future.isDone());
        assertEquals(2, future.get().getHeight());
    }

    @Test
    public void receiveCoins() throws Exception {
        // Quick check that we can actually receive coins.
        Transaction tx1 = createFakeTx(unitTestParams,
                                       Utils.toNanoCoins(1, 0),
                                       wallet.getKeys().get(0).toAddress(unitTestParams));
        Block b1 = createFakeBlock(blockStore, tx1).block;
        chain.add(b1);
        assertTrue(wallet.getBalance().compareTo(BigInteger.ZERO) > 0);
    }

    @Test
    public void merkleRoots() throws Exception {
        // Test that merkle root verification takes place when a relevant transaction is present and doesn't when
        // there isn't any such tx present (as an optimization).
        Transaction tx1 = createFakeTx(unitTestParams,
                                       Utils.toNanoCoins(1, 0),
                                       wallet.getKeys().get(0).toAddress(unitTestParams));
        Block b1 = createFakeBlock(blockStore, tx1).block;
        chain.add(b1);
        resetBlockStore();
        Sha256Hash hash = b1.getMerkleRoot();
        b1.setMerkleRoot(Sha256Hash.ZERO_HASH);
        try {
            chain.add(b1);
            fail();
        } catch (VerificationException e) {
            // Expected.
            b1.setMerkleRoot(hash);
        }
        // Now add a second block with no relevant transactions and then break it.
        Transaction tx2 = createFakeTx(unitTestParams, Utils.toNanoCoins(1, 0),
                                       new ECKey().toAddress(unitTestParams));
        Block b2 = createFakeBlock(blockStore, tx2).block;
        b2.getMerkleRoot();
        b2.setMerkleRoot(Sha256Hash.ZERO_HASH);
        b2.solve();
        chain.add(b2);  // Broken block is accepted because its contents don't matter to us.
    }

    @Test
    public void unconnectedBlocks() throws Exception {
        Block b1 = unitTestParams.getGenesisBlock().createNextBlock(coinbaseTo);
        Block b2 = b1.createNextBlock(coinbaseTo);
        Block b3 = b2.createNextBlock(coinbaseTo);
        // Connected.
        assertTrue(chain.add(b1));
        // Unconnected but stored. The head of the chain is still b1.
        assertFalse(chain.add(b3));
        assertEquals(chain.getChainHead().getHeader(), b1.cloneAsHeader());
        // Add in the middle block.
        assertTrue(chain.add(b2));
        assertEquals(chain.getChainHead().getHeader(), b3.cloneAsHeader());
    }

    @Test
    public void difficultyTransitions() throws Exception {
        // Add a bunch of blocks in a loop until we reach a difficulty transition point. The unit test params have an
        // artificially shortened period.
        Block prev = unitTestParams.getGenesisBlock();
        Utils.setMockClock(System.currentTimeMillis()/1000);
        for (int i = 0; i < unitTestParams.getInterval() - 1; i++) {
            Block newBlock = prev.createNextBlock(coinbaseTo, Utils.now().getTime()/1000);
            assertTrue(chain.add(newBlock));
            prev = newBlock;
            // The fake chain should seem to be "fast" for the purposes of difficulty calculations.
            Utils.rollMockClock(2);
        }
        // Now add another block that has no difficulty adjustment, it should be rejected.
        try {
            chain.add(prev.createNextBlock(coinbaseTo, Utils.now().getTime()/1000));
            fail();
        } catch (VerificationException e) {
        }
        // Create a new block with the right difficulty target given our blistering speed relative to the huge amount
        // of time it's supposed to take (set in the unit test network parameters).
        Block b = prev.createNextBlock(coinbaseTo, Utils.now().getTime()/1000);
        b.setDifficultyTarget(0x201fFFFFL);
        b.solve();
        assertTrue(chain.add(b));
        // Successfully traversed a difficulty transition period.
    }

    @Test
    public void badDifficulty() throws Exception {
        assertTrue(testNetChain.add(getBlock1()));
        Block b2 = getBlock2();
        assertTrue(testNetChain.add(b2));
        Block bad = new Block(testNet);
        // Merkle root can be anything here, doesn't matter.
        bad.setMerkleRoot(new Sha256Hash("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
        // Nonce was just some number that made the hash < difficulty limit set below, it can be anything.
        bad.setNonce(140548933);
        bad.setTime(1279242649);
        bad.setPrevBlockHash(b2.getHash());
        // We're going to make this block so easy 50% of solutions will pass, and check it gets rejected for having a
        // bad difficulty target. Unfortunately the encoding mechanism means we cannot make one that accepts all
        // solutions.
        bad.setDifficultyTarget(Block.EASIEST_DIFFICULTY_TARGET);
        try {
            testNetChain.add(bad);
            // The difficulty target above should be rejected on the grounds of being easier than the networks
            // allowable difficulty.
            fail();
        } catch (VerificationException e) {
            assertTrue(e.getMessage(), e.getCause().getMessage().contains("Difficulty target is bad"));
        }

        // Accept any level of difficulty now.
        BigInteger oldVal = testNet.getProofOfWorkLimit();
        testNet.setProofOfWorkLimit(new BigInteger
                ("00ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff", 16));
        try {
            testNetChain.add(bad);
            // We should not get here as the difficulty target should not be changing at this point.
            fail();
        } catch (VerificationException e) {
            assertTrue(e.getMessage(), e.getCause().getMessage().contains("Unexpected change in difficulty"));
        }
        testNet.setProofOfWorkLimit(oldVal);

        // TODO: Test difficulty change is not out of range when a transition period becomes valid.
    }

    @Test
    public void duplicates() throws Exception {
        // Adding a block twice should not have any effect, in particular it should not send the block to the wallet.
        Block b1 = unitTestParams.getGenesisBlock().createNextBlock(coinbaseTo);
        Block b2 = b1.createNextBlock(coinbaseTo);
        Block b3 = b2.createNextBlock(coinbaseTo);
        assertTrue(chain.add(b1));
        assertEquals(b1, block[0].getHeader());
        assertTrue(chain.add(b2));
        assertEquals(b2, block[0].getHeader());
        assertTrue(chain.add(b3));
        assertEquals(b3, block[0].getHeader());
        assertEquals(b3, chain.getChainHead().getHeader());
        assertTrue(chain.add(b2));
        assertEquals(b3, chain.getChainHead().getHeader());
        // Wallet was NOT called with the new block because the duplicate add was spotted.
        assertEquals(b3, block[0].getHeader());
    }

    @Test
    public void intraBlockDependencies() throws Exception {
        // Covers issue 166 in which transactions that depend on each other inside a block were not always being
        // considered relevant.
        Address somebodyElse = new ECKey().toAddress(unitTestParams);
        Block b1 = unitTestParams.getGenesisBlock().createNextBlock(somebodyElse);
        ECKey key = new ECKey();
        wallet.addKey(key);
        Address addr = key.toAddress(unitTestParams);
        // Create a tx that gives us some coins, and another that spends it to someone else in the same block.
        Transaction t1 = TestUtils.createFakeTx(unitTestParams, Utils.toNanoCoins(1, 0), addr);
        Transaction t2 = new Transaction(unitTestParams);
        t2.addInput(t1.getOutputs().get(0));
        t2.addOutput(Utils.toNanoCoins(2, 0), somebodyElse);
        b1.addTransaction(t1);
        b1.addTransaction(t2);
        b1.solve();
        chain.add(b1);
        assertEquals(BigInteger.ZERO, wallet.getBalance());
    }

    @Test
    public void coinbaseTransactionAvailability() throws Exception {
        // Check that a coinbase transaction is only available to spend after NetworkParameters.getSpendableCoinbaseDepth() blocks.

        // Create a second wallet to receive the coinbase spend.
        Wallet wallet2 = new Wallet(unitTestParams);
        ECKey receiveKey = new ECKey();
        wallet2.addKey(receiveKey);
        chain.addWallet(wallet2);

        Address addressToSendTo = receiveKey.toAddress(unitTestParams);

        // Create a block, sending the coinbase to the coinbaseTo address (which is in the wallet).
        Block b1 = unitTestParams.getGenesisBlock().createNextBlockWithCoinbase(wallet.getKeys().get(0).getPubKey());
        chain.add(b1);

        // Check a transaction has been received.
        assertNotNull(coinbaseTransaction);

        // The coinbase tx is not yet available to spend.
        assertEquals(BigInteger.ZERO, wallet.getBalance());
        assertEquals(wallet.getBalance(BalanceType.ESTIMATED), Utils.toNanoCoins(50, 0));
        assertTrue(!coinbaseTransaction.isMature());

        // Attempt to spend the coinbase - this should fail as the coinbase is not mature yet.
        try {
            wallet.createSend(addressToSendTo, Utils.toNanoCoins(49, 0));
            fail();
        } catch (InsufficientMoneyException e) {
        }

        // Check that the coinbase is unavailable to spend for the next spendableCoinbaseDepth - 2 blocks.
        for (int i = 0; i < unitTestParams.getSpendableCoinbaseDepth() - 2; i++) {
            // Non relevant tx - just for fake block creation.
            Transaction tx2 = createFakeTx(unitTestParams, Utils.toNanoCoins(1, 0),
                new ECKey().toAddress(unitTestParams));

            Block b2 = createFakeBlock(blockStore, tx2).block;
            chain.add(b2);

            // Wallet still does not have the coinbase transaction available for spend.
            assertEquals(BigInteger.ZERO, wallet.getBalance());
            assertEquals(wallet.getBalance(BalanceType.ESTIMATED), Utils.toNanoCoins(50, 0));

            // The coinbase transaction is still not mature.
            assertTrue(!coinbaseTransaction.isMature());

            // Attempt to spend the coinbase - this should fail.
            try {
                wallet.createSend(addressToSendTo, Utils.toNanoCoins(49, 0));
                fail();
            } catch (InsufficientMoneyException e) {
            }
        }

        // Give it one more block - should now be able to spend coinbase transaction. Non relevant tx.
        Transaction tx3 = createFakeTx(unitTestParams, Utils.toNanoCoins(1, 0), new ECKey().toAddress(unitTestParams));
        Block b3 = createFakeBlock(blockStore, tx3).block;
        chain.add(b3);

        // Wallet now has the coinbase transaction available for spend.
        assertEquals(wallet.getBalance(), Utils.toNanoCoins(50, 0));
        assertEquals(wallet.getBalance(BalanceType.ESTIMATED), Utils.toNanoCoins(50, 0));
        assertTrue(coinbaseTransaction.isMature());

        // Create a spend with the coinbase BTC to the address in the second wallet - this should now succeed.
        Transaction coinbaseSend2 = wallet.createSend(addressToSendTo, Utils.toNanoCoins(49, 0));
        assertNotNull(coinbaseSend2);

        // Commit the coinbaseSpend to the first wallet and check the balances decrement.
        wallet.commitTx(coinbaseSend2);
        assertEquals(wallet.getBalance(BalanceType.ESTIMATED), Utils.toNanoCoins(1, 0));
        // Available balance is zero as change has not been received from a block yet.
        assertEquals(wallet.getBalance(BalanceType.AVAILABLE), Utils.toNanoCoins(0, 0));

        // Give it one more block - change from coinbaseSpend should now be available in the first wallet.
        Block b4 = createFakeBlock(blockStore, coinbaseSend2).block;
        chain.add(b4);
        assertEquals(wallet.getBalance(BalanceType.AVAILABLE), Utils.toNanoCoins(1, 0));

        // Check the balances in the second wallet.
        assertEquals(wallet2.getBalance(BalanceType.ESTIMATED), Utils.toNanoCoins(49, 0));
        assertEquals(wallet2.getBalance(BalanceType.AVAILABLE), Utils.toNanoCoins(49, 0));
    }

    // Some blocks from the test net.
    private static Block getBlock1() throws Exception {
        Block b2 = new Block(mainNet);
        /*
        Could not verify block 2bc84fcd2c8b77264eba1a39516de8f4072d78d2e73d145ef6735e67fd3bc456
        v2 block:
        previous block: b8f534c4a2682f8d5ee37a6fc7ec1ff185a133774b971d9058c4d6e9a7a81311
        merkle root: 2c57e1409f9896989efbb5a3f352a38424b34f3a4dd68f1faadd46a1fff1196f
        time: [1377809916] Thu Aug 29 16:58:36 EDT 2013
        difficulty target (nBits): 457579461
        nonce: 1617239040
        */
        b2.setVersion(1);
        b2.setMerkleRoot(new Sha256Hash("22284e9b7b34072c0a2c4bf2c77f3c6f00df80f0485e4e6a86d5d7dff75db5f4"));
        b2.setDifficultyTarget(0x1d0b118d);
        b2.setNonce(969);
        b2.setTime(1318769442L);
        b2.setPrevBlockHash(new Sha256Hash("812c8cdf824da11cfbb31453e8284d1b9cdf8c9269bcbf7640d614f43cd7eac0"));
        System.out.println(b2.toString());
        assertEquals("7fd90d37349af9057e0dea890971a8c2fa34457f63edb7db116aec9fb0670874", b2.getHashAsString());
        b2.verifyHeader();
        return b2;
    }

    private static Block getBlock2() throws Exception {
        Block b1 = new Block(mainNet);
        b1.setVersion(2);
        b1.setMerkleRoot(new Sha256Hash("58718d9e5dbea2908708b7161dae5e69bbaef9bcb61ce7dc3c0f104ca5a832cc"));
        b1.setDifficultyTarget(457579461L);
        b1.setNonce(819999489L);
        b1.setTime(1377810040L);
        b1.setPrevBlockHash(new Sha256Hash("2bc84fcd2c8b77264eba1a39516de8f4072d78d2e73d145ef6735e67fd3bc456"));
        assertEquals("376da494aa83111552857ce6086ca61cdec64738d1852f93475f45b1ea7ba3cc", b1.getHashAsString());
        b1.verifyHeader();
        return b1;
    }

    @Test
    public void estimatedBlockTime() throws Exception {
        NetworkParameters params = MainNetParams.get();
        BlockChain prod = new BlockChain(params, new MemoryBlockStore(params));
        Date d = prod.estimateBlockTime(200000);
        // The actual date of block 200,000 was 2012-09-22 10:47:00
        assertEquals(new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ").parse("2015-07-26T21:51:05.000-0700"), d);
    }
}
