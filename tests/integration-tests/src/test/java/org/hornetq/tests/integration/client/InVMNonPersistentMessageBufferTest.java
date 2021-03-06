/*
 * Copyright 2009 Red Hat, Inc.
 * Red Hat licenses this file to you under the Apache License, version
 * 2.0 (the "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *    http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 */
package org.hornetq.tests.integration.client;
import org.junit.Before;
import org.junit.After;

import org.junit.Test;

import org.junit.Assert;

import org.hornetq.api.core.client.ClientConsumer;
import org.hornetq.api.core.client.ClientMessage;
import org.hornetq.api.core.client.ClientProducer;
import org.hornetq.api.core.client.ClientSession;
import org.hornetq.api.core.client.ClientSessionFactory;
import org.hornetq.api.core.client.ServerLocator;
import org.hornetq.core.protocol.core.impl.PacketImpl;
import org.hornetq.core.server.HornetQServer;
import org.hornetq.tests.util.RandomUtil;
import org.hornetq.tests.util.ServiceTestBase;
import org.hornetq.utils.DataConstants;

public class InVMNonPersistentMessageBufferTest extends ServiceTestBase
{
   public static final String address = "testaddress";

   public static final String queueName = "testqueue";

   private HornetQServer server;

   private ClientSession session;

   private ClientProducer producer;

   private ClientConsumer consumer;

   /*
    * Test message can be read after being sent
    * Message can be sent multiple times
    * After sending, local message can be read
    * After sending, local message body can be added to and sent
    * When reset message body it should only reset to after packet headers
    * Should not be able to read past end of body into encoded message
    */

   @Test
   public void testSimpleSendReceive() throws Exception
   {
      ClientMessage message = session.createMessage(false);

      final String body = RandomUtil.randomString();

      message.getBodyBuffer().writeString(body);

      ClientMessage received = sendAndReceive(message);

      Assert.assertNotNull(received);

      Assert.assertEquals(body, received.getBodyBuffer().readString());
   }

   @Test
   public void testSimpleSendReceiveWithEmptyBody() throws Exception
   {
      ClientMessage message = session.createMessage(false);

      ClientMessage received = sendAndReceive(message);

      Assert.assertNotNull(received);

      Assert.assertEquals(0, received.getBodySize());
   }

   @Test
   public void testSendSameMessageMultipleTimes() throws Exception
   {
      ClientMessage message = session.createMessage(false);

      final String body = RandomUtil.randomString();

      message.getBodyBuffer().writeString(body);

      int bodySize = message.getBodySize();

      for (int i = 0; i < 10; i++)
      {
         ClientMessage received = sendAndReceive(message);

         Assert.assertNotNull(received);

         Assert.assertEquals(bodySize, received.getBodySize());

         Assert.assertEquals(body, received.getBodyBuffer().readString());

         Assert.assertFalse(received.getBodyBuffer().readable());
      }
   }

   @Test
   public void testSendMessageResetSendAgainDifferentBody() throws Exception
   {
      ClientMessage message = session.createMessage(false);

      String body = RandomUtil.randomString();

      for (int i = 0; i < 10; i++)
      {
         // Make the body a bit longer each time
         body += "XX";

         message.getBodyBuffer().writeString(body);

         int bodySize = message.getBodySize();

         ClientMessage received = sendAndReceive(message);

         Assert.assertNotNull(received);

         Assert.assertEquals(bodySize, received.getBodySize());

         Assert.assertEquals(body, received.getBodyBuffer().readString());

         Assert.assertFalse(received.getBodyBuffer().readable());

         message.getBodyBuffer().clear();

         Assert.assertEquals(PacketImpl.PACKET_HEADERS_SIZE + DataConstants.SIZE_INT, message.getBodyBuffer()
                                                                                             .writerIndex());

         Assert.assertEquals(PacketImpl.PACKET_HEADERS_SIZE + DataConstants.SIZE_INT, message.getBodyBuffer()
                                                                                             .readerIndex());
      }
   }

   @Test
   public void testCannotReadPastEndOfMessageBody() throws Exception
   {
      ClientMessage message = session.createMessage(false);

      final String body = RandomUtil.randomString();

      message.getBodyBuffer().writeString(body);

      ClientMessage received = sendAndReceive(message);

      Assert.assertNotNull(received);

      Assert.assertEquals(body, received.getBodyBuffer().readString());

      try
      {
         received.getBodyBuffer().readByte();

         Assert.fail("Should throw exception");
      }
      catch (IndexOutOfBoundsException e)
      {
         // OK
      }
   }

   @Test
   public void testCanReReadBodyAfterReaderReset() throws Exception
   {
      ClientMessage message = session.createMessage(false);

      final String body = RandomUtil.randomString();

      message.getBodyBuffer().writeString(body);

      Assert.assertEquals(PacketImpl.PACKET_HEADERS_SIZE + DataConstants.SIZE_INT, message.getBodyBuffer()
                                                                                          .readerIndex());

      String body2 = message.getBodyBuffer().readString();

      Assert.assertEquals(body, body2);

      message.getBodyBuffer().resetReaderIndex();

      Assert.assertEquals(PacketImpl.PACKET_HEADERS_SIZE + DataConstants.SIZE_INT, message.getBodyBuffer()
                                                                                          .readerIndex());

      String body3 = message.getBodyBuffer().readString();

      Assert.assertEquals(body, body3);

      ClientMessage received = sendAndReceive(message);

      Assert.assertNotNull(received);

      Assert.assertEquals(body, received.getBodyBuffer().readString());

      received.getBodyBuffer().resetReaderIndex();

      Assert.assertEquals(PacketImpl.PACKET_HEADERS_SIZE + DataConstants.SIZE_INT, received.getBodyBuffer()
                                                                                           .readerIndex());

      String body4 = received.getBodyBuffer().readString();

      Assert.assertEquals(body, body4);

   }

   protected ServerLocator createFactory() throws Exception
   {
      if (isNetty())
      {
         return createNettyNonHALocator();
      }
      else
      {
         return createInVMNonHALocator();
      }
   }

   protected boolean isNetty()
   {
      return false;
   }

   protected boolean isPersistent()
   {
      return false;
   }

   @Override
   @Before
   public void setUp() throws Exception
   {
      super.setUp();

      server = createServer(isPersistent(), isNetty());

      server.start();

      ServerLocator locator = createFactory();

      ClientSessionFactory cf = createSessionFactory(locator);

      session = cf.createSession();

      session.createQueue(InVMNonPersistentMessageBufferTest.address, InVMNonPersistentMessageBufferTest.queueName);

      producer = session.createProducer(InVMNonPersistentMessageBufferTest.address);

      consumer = session.createConsumer(InVMNonPersistentMessageBufferTest.queueName);

      session.start();
   }

   @Override
   @After
   public void tearDown() throws Exception
   {
      if (session != null)
      {
         consumer.close();

         session.deleteQueue(InVMNonPersistentMessageBufferTest.queueName);

         session.close();
      }

      super.tearDown();
   }

   private ClientMessage sendAndReceive(final ClientMessage message) throws Exception
   {
      producer.send(message);

      ClientMessage received = consumer.receive(10000);

      return received;
   }

}
