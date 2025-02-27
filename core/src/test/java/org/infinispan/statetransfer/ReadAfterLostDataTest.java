package org.infinispan.statetransfer;

import static org.infinispan.test.TestingUtil.crashCacheManagers;
import static org.infinispan.test.TestingUtil.installNewView;
import static org.testng.AssertJUnit.assertEquals;
import static org.testng.AssertJUnit.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import org.infinispan.AdvancedCache;
import org.infinispan.Cache;
import org.infinispan.commands.topology.TopologyUpdateCommand;
import org.infinispan.configuration.cache.CacheMode;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.functional.EntryView.ReadEntryView;
import org.infinispan.functional.EntryView.ReadWriteEntryView;
import org.infinispan.functional.FunctionalMap.ReadOnlyMap;
import org.infinispan.functional.FunctionalMap.ReadWriteMap;
import org.infinispan.functional.impl.FunctionalMapImpl;
import org.infinispan.functional.impl.ReadOnlyMapImpl;
import org.infinispan.functional.impl.ReadWriteMapImpl;
import org.infinispan.marshall.core.MarshallableFunctions;
import org.infinispan.partitionhandling.PartitionHandling;
import org.infinispan.registry.InternalCacheRegistry;
import org.infinispan.test.MultipleCacheManagersTest;
import org.infinispan.test.TestDataSCI;
import org.infinispan.test.TestingUtil;
import org.infinispan.test.fwk.CleanupAfterMethod;
import org.infinispan.test.fwk.InCacheMode;
import org.infinispan.test.fwk.TransportFlags;
import org.infinispan.topology.ClusterTopologyManager;
import org.infinispan.topology.HeartBeatCommand;
import org.infinispan.util.ControlledTransport;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

/**
 * Tests operations executed when the actual owners are lost, with partition handling disabled (AP mode).
 */
@Test(groups = "functional", testName = "statetransfer.ReadAfterLostDataTest")
@InCacheMode(CacheMode.DIST_SYNC)
@CleanupAfterMethod
public class ReadAfterLostDataTest extends MultipleCacheManagersTest {
   private final List<Runnable> cleanup = new ArrayList<>();

   @Override
   protected void createCacheManagers() throws Throwable {
      ConfigurationBuilder cb = new ConfigurationBuilder();
      cb.clustering().cacheMode(cacheMode)
            .partitionHandling().whenSplit(PartitionHandling.ALLOW_READ_WRITES);
      createClusteredCaches(4, TestDataSCI.INSTANCE, cb, new TransportFlags().withFD(true).withMerge(true));
   }

   @AfterMethod
   protected void cleanup() {
      cleanup.forEach(Runnable::run);
      cleanup.clear();
   }

   public void testGet() throws Exception {
      test(ReadAfterLostDataTest::get, false, false);
   }

   public void testGetBeforeTopologyUpdate() throws Exception {
      test(ReadAfterLostDataTest::get, false, true);
   }

   public void testGetAll() throws Exception {
      test(ReadAfterLostDataTest::getAll, false, false);
   }

   public void testGetAllBeforeTopologyUpdate() throws Exception {
      test(ReadAfterLostDataTest::getAll, false, true);
   }

   public void testPut() throws Exception {
      test(ReadAfterLostDataTest::put, true, false);
   }

   public void testRemove() throws Exception {
      test(ReadAfterLostDataTest::remove, true, false);
   }

   public void testReplace() throws Exception {
      test(ReadAfterLostDataTest::replace, true, false);
   }

   //TODO: We don't test put/remove/replace/read-write before topology update as with triangle these commands
   // invoke RpcManager.sendTo that does not throw an exception when the target is not in view anymore.
   // These commands rely on next topology (that is blocked) causing the OutdatedTopologyException.
   // We'd need to execute them in parallel, wait until all of them use RpcManager.sendTo and then unblock
   // the topology change. That's rather too much tied to the actual implementation.

   public void testPutMap() throws Exception {
      test(ReadAfterLostDataTest::putMap, true, false);
   }

   public void testPutMapBeforeTopologyUpdate() throws Exception {
      test(ReadAfterLostDataTest::putMap, true, true);
   }

   public void testRead() throws Exception {
      test(ReadAfterLostDataTest::read, false, false);
   }

   public void testReadBeforeTopologyUpdate() throws Exception {
      test(ReadAfterLostDataTest::read, false, true);
   }

   public void testReadMany() throws Exception {
      test(ReadAfterLostDataTest::readMany, false, false);
   }

   public void testReadManyBeforeTopologyUpdate() throws Exception {
      test(ReadAfterLostDataTest::readMany, false, true);
   }

   public void testReadWrite() throws Exception {
      test(ReadAfterLostDataTest::readWrite, false, false);
   }

   public void testReadWriteMany() throws Exception {
      test(ReadAfterLostDataTest::readWriteMany, false, false);
   }

   public void testReadWriteManyBeforeTopologyUpdate() throws Exception {
      test(ReadAfterLostDataTest::readWriteMany, true, true);
   }


   protected void test(BiFunction<Cache<Object, Object>, Collection<?>, Map<?, ?>> operation, boolean write, boolean blockUpdates) throws Exception {
      List<Object> keys = new ArrayList<>();
      keys.add(getKeyForCache(cache(0), cache(1))); // both owners in p0
      keys.add(getKeyForCache(cache(0), cache(2))); // primary in p0
      keys.add(getKeyForCache(cache(2), cache(1))); // backup in p0
      keys.add(getKeyForCache(cache(2), cache(3))); // nothing in p0

      for (int i = 0; i < keys.size(); ++i) {
         cache(0).put(keys.get(i), "value" + i);
      }

      AdvancedCache<Object, Object> coordCache = advancedCache(0);
      assertTrue(coordCache.getCacheManager().isCoordinator());
      ClusterTopologyManager clusterTopologyManager = TestingUtil.extractComponent(coordCache, ClusterTopologyManager.class);
      clusterTopologyManager.setRebalancingEnabled(false);

      ControlledTransport controlledTransport = null;
      if (blockUpdates) {
         controlledTransport = ControlledTransport.replace(coordCache);
         controlledTransport.excludeCacheCommands();
         controlledTransport.excludeCommands(HeartBeatCommand.class);
         cleanup.add(controlledTransport::stopBlocking);

         InternalCacheRegistry icr = coordCache.getCacheManager().getGlobalComponentRegistry().getComponent(InternalCacheRegistry.class);
         int cacheCount = icr.getInternalCacheNames().size() + 1; // include default
         List<CompletableFuture<ControlledTransport.BlockedRequest<TopologyUpdateCommand>>> topologyUpdateRequests = new ArrayList<>();
         // Block the sending of the TopologyUpdateCommand until a command asks for the transaction data future
         for(int i = 0; i < cacheCount; i++) {
            topologyUpdateRequests.add(controlledTransport.expectCommandAsync(TopologyUpdateCommand.class));
         }

         CompletableFuture<Void> firstTransactionDataRequest = new CompletableFuture<>();
         for (Cache<?, ?> c : Arrays.asList(cache(0), cache(1))) {
            int currentTopology = c.getAdvancedCache().getDistributionManager().getCacheTopology().getTopologyId();
            // Because all responses are CacheNotFoundResponses, retries will block to wait for a new topology
            // Even reads block, because in general the values might have been copied to the write-only owners
            TestingUtil.wrapComponent(c, StateTransferLock.class,
                  stl -> new UnblockingStateTransferLock(stl, currentTopology + 1, firstTransactionDataRequest));
         }
         firstTransactionDataRequest.thenAccept(__ -> {
            for(CompletableFuture<ControlledTransport.BlockedRequest<TopologyUpdateCommand>> request : topologyUpdateRequests) {
               request.thenAccept(ControlledTransport.BlockedRequest::send);
            }
         });
      }

      crashCacheManagers(manager(2), manager(3));
      installNewView(manager(0), manager(1));

      invokeOperation(cache(0), operation, keys);
      // Don't do the second check if first operation modified the data
      if (!write) {
         invokeOperation(cache(1), operation, keys);
      }

      if (controlledTransport != null) {
         expectNoWaiters(controlledTransport);
      }
   }

   private void expectNoWaiters(ControlledTransport transport) {
      eventually(() -> "Should not have waiters, found: " + transport.currentWaitersSize(),
            () -> transport.currentWaitersSize() == 0, 10, TimeUnit.SECONDS);
   }

   private void invokeOperation(Cache<Object, Object> cache, BiFunction<Cache<Object, Object>, Collection<?>, Map<?, ?>> operation, List<Object> keys) {
      Map<?, ?> result = operation.apply(cache, keys);
      assertEquals("value0", result.get(keys.get(0)));
      assertEquals("value1", result.get(keys.get(1)));
      assertEquals("value2", result.get(keys.get(2)));
      assertEquals(null, result.get(keys.get(3)));
      assertEquals(result.toString(), 3, result.size());
   }

   private static Map<?, ?> get(Cache<Object, Object> cache, Collection<?> keys) {
      Map<Object, Object> map = new HashMap<>();
      for (Object key : keys) {
         Object value = cache.get(key);
         if (value != null) {
            map.put(key, value);
         }
      }
      return map;
   }

   private static Map<?, ?> getAll(Cache<Object, Object> cache, Collection<?> keys) {
      return cache.getAdvancedCache().getAll(new HashSet<>(keys));
   }

   private static Map<?, ?> put(Cache<Object, Object> cache, Collection<?> keys) {
      Map<Object, Object> map = new HashMap<>();
      int i = 0;
      for (Object key : keys) {
         Object value = cache.put(key, "other" + (i++));
         if (value != null) {
            map.put(key, value);
         }
      }
      return map;
   }

   private static Map<?, ?> putMap(Cache<Object, Object> cache, Collection<?> keys) {
      Map<Object, Object> writeMap = new HashMap<>();
      int i = 0;
      for (Object key : keys) {
         writeMap.put(key, "other" + (i++));
      }
      return cache.getAdvancedCache().getAndPutAll(writeMap);
   }

   private static Map<?, ?> remove(Cache<Object, Object> cache, Collection<?> keys) {
      Map<Object, Object> map = new HashMap<>();
      for (Object key : keys) {
         Object value = cache.remove(key);
         if (value != null) {
            map.put(key, value);
         }
      }
      return map;
   }

   private static Map<?, ?> replace(Cache<Object, Object> cache, Collection<?> keys) {
      Map<Object, Object> map = new HashMap<>();
      int i = 0;
      for (Object key : keys) {
         Object value = cache.replace(key, "other" + (i++));
         if (value != null) {
            map.put(key, value);
         }
      }
      return map;
   }

   private static Map<?, ?> read(Cache<Object, Object> cache, Collection<?> keys) {
      ReadOnlyMap<Object, Object> ro = ReadOnlyMapImpl.create(FunctionalMapImpl.create(cache.getAdvancedCache()));
      Map<Object, Object> map = new HashMap<>();
      for (Object key : keys) {
         ro.eval(key, MarshallableFunctions.identity()).join().find().ifPresent(value -> map.put(key, value));
      }
      return map;
   }

   private static Map<?, ?> readMany(Cache<Object, Object> cache, Collection<?> keys) {
      ReadOnlyMap<Object, Object> ro = ReadOnlyMapImpl.create(FunctionalMapImpl.create(cache.getAdvancedCache()));
      return ro.evalMany(new HashSet<>(keys), MarshallableFunctions.identity())
            .filter(view -> view.find().isPresent())
            .collect(Collectors.toMap(ReadEntryView::key, ReadEntryView::get));
   }

   private static Map<?, ?> readWrite(Cache<Object, Object> cache, Collection<?> keys) {
      ReadWriteMap<Object, Object> rw = ReadWriteMapImpl.create(FunctionalMapImpl.create(cache.getAdvancedCache()));
      Map<Object, Object> map = new HashMap<>();
      for (Object key : keys) {
         rw.eval(key, MarshallableFunctions.identity()).join().find().ifPresent(value -> map.put(key, value));
      }
      return map;
   }

   private static Map<?, ?> readWriteMany(Cache<Object, Object> cache, Collection<?> keys) {
      ReadWriteMap<Object, Object> ro = ReadWriteMapImpl.create(FunctionalMapImpl.create(cache.getAdvancedCache()));
      return ro.evalMany(new HashSet<>(keys), MarshallableFunctions.identity())
            .filter(view -> view.find().isPresent())
            .collect(Collectors.toMap(ReadWriteEntryView::key, ReadWriteEntryView::get));
   }

   private static class UnblockingStateTransferLock extends DelegatingStateTransferLock {
      private final int topologyId;
      private final CompletableFuture<Void> transactionDataRequestFuture;

      public UnblockingStateTransferLock(StateTransferLock delegate, int topologyId,
                                         CompletableFuture<Void> transactionDataRequestFuture) {
         super(delegate);
         this.topologyId = topologyId;
         this.transactionDataRequestFuture = transactionDataRequestFuture;
      }

      @Override
      public CompletionStage<Void> transactionDataFuture(int expectedTopologyId) {
         if (expectedTopologyId >= topologyId) {
            log.tracef("Completing future for transaction data request with topology %d", expectedTopologyId);
            transactionDataRequestFuture.complete(null);
         }
         return super.transactionDataFuture(expectedTopologyId);
      }
   }
}
