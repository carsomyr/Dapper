/**
 * <p>
 * Copyright (C) 2008 The Regents of the University of California<br />
 * All rights reserved.
 * </p>
 * <p>
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the
 * following conditions are met:
 * </p>
 * <ul>
 * <li>Redistributions of source code must retain the above copyright notice, this list of conditions and the following
 * disclaimer.</li>
 * <li>Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the
 * following disclaimer in the documentation and/or other materials provided with the distribution.</li>
 * <li>Neither the name of the author nor the names of any contributors may be used to endorse or promote products
 * derived from this software without specific prior written permission.</li>
 * </ul>
 * <p>
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
 * INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 * </p>
 */

package dapper.client;

import static dapper.event.ControlEvent.ControlEventType.PREPARE_ACK;
import static dapper.event.ControlEvent.ControlEventType.REFRESH;
import static dapper.event.ControlEvent.ControlEventType.RESOURCE_ACK;
import static dapper.event.ControlEvent.ControlEventType.SHUTDOWN;

import java.io.IOException;
import java.net.InetSocketAddress;

import shared.event.Source;
import shared.net.Connection.InitializationType;
import shared.net.SynchronousManagedConnection;
import shared.util.Control;
import shared.util.CoreThread;
import dapper.AsynchronousBase;
import dapper.event.AddressEvent;
import dapper.event.ControlEvent;
import dapper.event.ControlEventConnection;
import dapper.event.DataRequestEvent;
import dapper.event.ErrorEvent;
import dapper.event.ExecuteAckEvent;
import dapper.event.ResetEvent;
import dapper.event.ResourceEvent;
import dapper.event.SourceType;
import dapper.event.StreamReadyEvent;

/**
 * A class that houses the Dapper client logic.
 * 
 * @apiviz.composedOf dapper.client.ClientJob
 * @apiviz.composedOf dapper.client.ClientConnector
 * @author Roy Liu
 */
public class ClientLogic {

    final AsynchronousBase base;
    final InetSocketAddress localAddress;
    final InetSocketAddress remoteAddress;
    final String domain;
    final ClientProcessor cp;

    ControlEventConnection server;

    ClientJob job;
    ClientConnector connector;

    /**
     * Default constructor.
     */
    public ClientLogic(AsynchronousBase base, //
            InetSocketAddress localAddress, //
            InetSocketAddress remoteAddress, //
            String domain, //
            ClientProcessor cp) {

        this.base = base;
        this.localAddress = localAddress;
        this.remoteAddress = remoteAddress;
        this.domain = domain;
        this.cp = cp;

        this.server = null;

        this.job = null;
        this.connector = null;
    }

    // INTERNAL LOGIC

    /**
     * Handles a {@link ResetEvent}.
     */
    protected void handleReset(ResetEvent evt) {

        Source<ControlEvent, SourceType> source = evt.getSource();
        Object tag = evt.get();

        // Forward the event if it was locally generated. Otherwise, it was from the server.
        if (source == this.cp && (tag == this.job || tag == this.connector)) {

            this.server.onRemote(evt);

        } else if (source == this.server && tag == null) {

            Client.getLog().info(String.format("Received error from server: %s.", //
                    evt.getException().getMessage()));

        } else {

            return;
        }

        Control.close(this.job);
        this.job = null;

        Control.close(this.connector);
        this.connector = null;

        this.cp.setStatus(ClientStatus.WAIT);
    }

    /**
     * Transitions from {@link ClientStatus#IDLE} to {@link ClientStatus#CONNECT}.
     */
    protected void handleIdleToConnect() {

        final ControlEventConnection server = this.base.createControlConnection(this.cp);
        server.setHandler(this.cp);

        new CoreThread("Server Connector Thread") {

            @Override
            protected void runUnchecked() throws Exception {

                ClientLogic cl = ClientLogic.this;

                server.init(InitializationType.CONNECT, cl.remoteAddress).get();
                server.onRemote(new AddressEvent(new InetSocketAddress( //
                        server.getLocalAddress().getAddress(), //
                        cl.localAddress.getPort()), //
                        cl.domain, null));
            }

            @Override
            protected void runCatch(Throwable t) {
                // No need to handle -- Manager will signal client processor on failure.
            }

        }.start();

        this.cp.setStatus(ClientStatus.CONNECT);
    }

    /**
     * Handles a {@link StreamReadyEvent}.
     */
    protected void handleStreamReady(StreamReadyEvent evt) {

        SynchronousManagedConnection smc = evt.getConnection();

        if (this.job != null) {

            // Register the connection with the current job.
            this.job.registerStream(evt.getIdentifier(), smc);

        } else {

            // If the job doesn't exist, then the connection must be erroneous.
            Control.close(smc);
        }

        // Interrupt self.
        this.cp.onLocal(new ControlEvent(REFRESH, this.cp));
    }

    /**
     * Handles a refresh.
     */
    protected void handleRefresh() {

        // Acknowledge completion of preparations.
        if (this.job.isReady()) {
            this.server.onRemote(new ControlEvent(PREPARE_ACK, null));
        }
    }

    /**
     * Handles an {@link ExecuteAckEvent}.
     */
    protected void handleExecuteSuccess(ExecuteAckEvent evt) {

        if (evt.get() != this.job) {
            return;
        }

        this.server.onRemote(evt);

        Control.close(this.job);
        this.job = null;

        Control.close(this.connector);
        this.connector = null;

        this.cp.setStatus(ClientStatus.WAIT);
    }

    // EXTERNAL LOGIC

    /**
     * Handles an end-of-stream.
     */
    protected void handleEOS(ControlEventConnection connection) {
        handleError(new ErrorEvent(new IOException("End-of-stream encountered"), connection));
    }

    /**
     * Handles an {@link ErrorEvent}.
     */
    protected void handleError(ErrorEvent evt) {

        Client.getLog().info(String.format("Received error from %s: %s. Shutting down.", //
                evt.getSource(), evt.getError().getMessage()));

        this.cp.onLocal(new ControlEvent(SHUTDOWN, this.cp));

        this.cp.setStatus(ClientStatus.SHUTDOWN);
    }

    /**
     * Transitions from {@link ClientStatus#CONNECT} to {@link ClientStatus#WAIT}.
     */
    protected void handleConnectToWait(ControlEventConnection server) {

        // Maintain a reference to the server connection.
        this.server = server;

        this.cp.setStatus(ClientStatus.WAIT);
    }

    /**
     * Transitions from {@link ClientStatus#WAIT} to {@link ClientStatus#RESOURCE}.
     */
    protected void handleWaitToResource(ResourceEvent evt) {

        evt.getSource().onRemote(new ControlEvent(RESOURCE_ACK, null));

        // Allocate, but do not start, a job thread and a connector thread.
        this.job = new ClientJob(evt, this.base, this.cp);
        this.connector = new ClientConnector(this.job.getConnectResources(), this.base, this.cp);

        this.cp.setStatus(ClientStatus.RESOURCE);
    }

    /**
     * Transitions from {@link ClientStatus#RESOURCE} to {@link ClientStatus#PREPARE}.
     */
    protected void handleResourceToPrepare() {

        // Start connecting to remote hosts.
        this.connector.start();

        // Interrupt self.
        this.cp.onLocal(new ControlEvent(REFRESH, this.cp));

        this.cp.setStatus(ClientStatus.PREPARE);
    }

    /**
     * Transitions from {@link ClientStatus#PREPARE} to {@link ClientStatus#EXECUTE}.
     */
    protected void handlePrepareToExecute() {

        // Start the job.
        this.job.start();

        this.cp.setStatus(ClientStatus.EXECUTE);
    }

    /**
     * Handles a {@link DataRequestEvent}.
     */
    protected void handleDataRequest(DataRequestEvent evt) {

        Source<ControlEvent, SourceType> source = evt.getSource();

        // Forward the event if it was locally generated. Otherwise, it was from the server.
        if (source == this.cp) {

            this.server.onRemote(evt);

        } else if (source == this.server) {

            this.job.registerData(evt.getPathname(), evt.getData());

        } else {

            throw new AssertionError("Control should never reach here");
        }
    }
}
