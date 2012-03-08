package org.hornetq.core.server.impl;

import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.hornetq.api.core.Pair;
import org.hornetq.api.core.TransportConfiguration;
import org.hornetq.api.core.client.ClientSessionFactory;
import org.hornetq.api.core.client.ClusterTopologyListener;
import org.hornetq.api.core.client.HornetQClient;
import org.hornetq.api.core.client.ServerLocator;
import org.hornetq.core.client.impl.ServerLocatorImpl;

/**
 * Manages a quorum of servers used to determine whether a given server is running or not.
 * <p>
 * The use case scenario is an eventual connection loss between the live and the backup, where the
 * quorum will help a remote backup in deciding whether to replace its 'live' server or to wait for
 * it.
 */
public final class QuorumManager implements ClusterTopologyListener
{

   // volatile boolean started;
   private final ServerLocator locator;
   private String targetServerID = "";
   private final Map<String, Pair<TransportConfiguration, TransportConfiguration>> nodes =
            new ConcurrentHashMap<String, Pair<TransportConfiguration, TransportConfiguration>>();

   private final ExecutorService executor;

   /** safety parameter to make _sure_ we get out of await() */
   private static final int LATCH_TIMEOUT = 60;
   private static final long DISCOVERY_TIMEOUT = 5;

   public QuorumManager(ServerLocator serverLocator, ExecutorService executor)
   {
      this.executor = executor;
      this.locator = serverLocator;
      locator.addClusterTopologyListener(this);
   }

   @Override
   public void nodeUP(long eventUID, String nodeID, Pair<TransportConfiguration, TransportConfiguration> connectorPair,
            boolean last)
   {
      if (targetServerID.equals(nodeID))
      {
         return;
      }
      nodes.put(nodeID, connectorPair);
   }

   @Override
   public void nodeDown(long eventUID, String nodeID)
   {
      if (targetServerID.equals(nodeID))
      {
         if (!targetServerID.isEmpty())
            synchronized (this)
            {
               notify();
            }
      }
      nodes.remove(nodeID);
   }

   public void setLiveID(String liveID)
   {
      targetServerID = liveID;
   }

   public boolean isNodeDown()
   {
      if (nodes.size() == 0)
      {
         return true;
      }

      final int size = nodes.size();
      Set<ServerLocator> locatorsList = new HashSet<ServerLocator>(size);
      AtomicInteger pingCount = new AtomicInteger(0);
      final CountDownLatch latch = new CountDownLatch(size);
      try
      {
         for (Entry<String, Pair<TransportConfiguration, TransportConfiguration>> pair : nodes.entrySet())
         {
            if (targetServerID.equals(pair.getKey()))
               continue;
            TransportConfiguration serverTC = pair.getValue().getA();
            ServerLocatorImpl locator = (ServerLocatorImpl)HornetQClient.createServerLocatorWithoutHA(serverTC);
            locatorsList.add(locator);
            executor.submit(new ServerConnect(latch, pingCount, locator));
         }
         // Some servers may have disappeared between the latch creation
         for (int i = 0; i < size - locatorsList.size(); i++)
         {
            latch.countDown();
         }
         try
         {
            latch.await(LATCH_TIMEOUT, TimeUnit.SECONDS);
         }
         catch (InterruptedException interruption)
         {
            // No-op. As the best the quorum can do now is to return the latest number it has
         }
         return pingCount.get() * 2 >= locatorsList.size();
      }
      finally
      {
         for (ServerLocator locator: locatorsList){
            try
            {
               locator.close();
            }
            catch (Exception e)
            {
               // no-op
            }
         }
      }
   }

   private static class ServerConnect implements Runnable
   {
      private final ServerLocatorImpl locator;
      private final CountDownLatch latch;
      private final AtomicInteger count;

      public ServerConnect(CountDownLatch latch, AtomicInteger count, ServerLocatorImpl serverLocator)
      {
         locator = serverLocator;
         this.latch = latch;
         this.count = count;
      }

      @Override
      public void run()
      {
         locator.setReconnectAttempts(0);
         locator.getDiscoveryGroupConfiguration().setDiscoveryInitialWaitTimeout(DISCOVERY_TIMEOUT);

         final ClientSessionFactory liveServerSessionFactory;
         try
         {
            liveServerSessionFactory = locator.connect();
            if (liveServerSessionFactory != null)
            {
               count.incrementAndGet();
            }
         }
         catch (Exception e)
         {
            // no-op
         }
         finally
         {
            latch.countDown();
            locator.close();
         }
      }
   }
}
