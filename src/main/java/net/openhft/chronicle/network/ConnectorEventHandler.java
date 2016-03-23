/*
 *
 *  *     Copyright (C) 2016  higherfrequencytrading.com
 *  *
 *  *     This program is free software: you can redistribute it and/or modify
 *  *     it under the terms of the GNU Lesser General Public License as published by
 *  *     the Free Software Foundation, either version 3 of the License.
 *  *
 *  *     This program is distributed in the hope that it will be useful,
 *  *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  *     GNU Lesser General Public License for more details.
 *  *
 *  *     You should have received a copy of the GNU Lesser General Public License
 *  *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package net.openhft.chronicle.network;

import net.openhft.chronicle.core.threads.EventHandler;
import net.openhft.chronicle.core.threads.EventLoop;
import net.openhft.chronicle.core.threads.HandlerPriority;
import net.openhft.chronicle.core.threads.InvalidEventHandlerException;
import net.openhft.chronicle.network.api.TcpHandler;
import net.openhft.chronicle.network.api.session.SessionDetailsProvider;
import net.openhft.chronicle.threads.LongPauser;
import net.openhft.chronicle.threads.Pauser;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Created by daniel on 09/02/2016. This class handles the creation, running and monitoring of
 * client connections
 */
public class ConnectorEventHandler implements EventHandler, Closeable {
    private static final Logger LOG = LoggerFactory.getLogger(ConnectorEventHandler.class);

    @NotNull
    private final Function<ConnectionDetails, TcpHandler> tcpHandlerSupplier;
    @NotNull
    private final Supplier<SessionDetailsProvider> sessionDetailsSupplier;

    private final Map<String, SocketChannel> descriptionToChannel = new ConcurrentHashMap<>();
    private final Pauser pauser = new LongPauser(0, 0, 5, 5, TimeUnit.SECONDS);
    private EventLoop eventLoop;
    private Map<String, ConnectionDetails> nameToConnectionDetails;


    public ConnectorEventHandler(@NotNull Map<String, ConnectionDetails> nameToConnectionDetails,
                                 @NotNull final Function<ConnectionDetails, TcpHandler> tcpHandlerSupplier,
                                 @NotNull final Supplier<SessionDetailsProvider>
                                         sessionDetailsSupplier) throws IOException {
        this.nameToConnectionDetails = nameToConnectionDetails;
        this.tcpHandlerSupplier = tcpHandlerSupplier;
        this.sessionDetailsSupplier = sessionDetailsSupplier;
    }

    @Override
    public boolean action() throws InvalidEventHandlerException, InterruptedException {
        nameToConnectionDetails.forEach((k, connectionDetails) -> {
            try {
                SocketChannel socketChannel = descriptionToChannel.get(k);

                if (socketChannel == null) {
                    if (connectionDetails.isDisable()) {
                        //we shouldn't create anything
                        return;
                    }
                    socketChannel = TCPRegistry.createSocketChannel(connectionDetails.getHostNameDescription());
                    socketChannel.socket().setTcpNoDelay(true);
                    socketChannel.socket().setSendBufferSize(1 << 20);
                    socketChannel.socket().setReceiveBufferSize(1 << 20);
                    socketChannel.configureBlocking(false);
                    descriptionToChannel.put(k, socketChannel);
                    connectionDetails.setConnected(true);
                    final SessionDetailsProvider sessionDetails = sessionDetailsSupplier.get();

                    sessionDetails.clientAddress((InetSocketAddress) socketChannel.getRemoteAddress());
                    connectionDetails.socketChannel(socketChannel);
                    final TcpEventHandler evntHandler = new TcpEventHandler(connectionDetails);
                    evntHandler.tcpHandler(tcpHandlerSupplier.apply(connectionDetails));
                    eventLoop.addHandler(evntHandler);
                } else if (socketChannel.isOpen()) {
                    //the socketChannel is doing fine
                    //check whether it should be disabled
                    if (connectionDetails.isDisable()) {
                        socketChannel.close();
                        connectionDetails.setConnected(false);
                        descriptionToChannel.remove(k);
                    }
                } else {
                    //the socketChannel has disconnected
                    connectionDetails.setConnected(false);
                    descriptionToChannel.remove(k);
                }
            } catch (ConnectException e) {
                //Not a problem try again next time round
                LOG.error(k + e.getMessage());
            } catch (IOException e) {
                LOG.error(k + e.getMessage());

            }
        });

        pauser.pause();

        return false;
    }

    public void updateConnectionDetails(ConnectionDetails connectionDetails) {
        //todo this is not strictly necessary
        nameToConnectionDetails.put(connectionDetails.getID(), connectionDetails);
        forceRescan();
    }

    /**
     * By default the connections are monitored every 5 seconds If you want to force the map to
     * check immediately use forceRescan
     */
    public void forceRescan() {
        pauser.unpause();
    }


    @Override
    public void eventLoop(EventLoop eventLoop) {
        this.eventLoop = eventLoop;
    }

    @NotNull
    @Override
    public HandlerPriority priority() {
        return HandlerPriority.BLOCKING;
    }

    @Override
    public void close() throws IOException {

    }

}