/*
 *  Copyright (C) 2008 Pingtel Corp., certain elements licensed under a Contributor Agreement.
 *  Contributors retain copyright to elements licensed under a Contributor Agreement.
 *  Licensed to the User under the LGPL license.
 *
 */
package org.sipfoundry.sipxbridge;

import gov.nist.javax.sdp.MediaDescriptionImpl;
import gov.nist.javax.sip.DialogExt;
import gov.nist.javax.sip.SipStackExt;
import gov.nist.javax.sip.TransactionExt;
import gov.nist.javax.sip.header.HeaderFactoryExt;
import gov.nist.javax.sip.header.extensions.ReferredByHeader;
import gov.nist.javax.sip.header.extensions.ReplacesHeader;

import java.io.IOException;
import java.net.URLDecoder;
import java.text.ParseException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Random;
import java.util.Set;

import javax.sdp.MediaDescription;
import javax.sdp.SdpFactory;
import javax.sdp.SdpParseException;
import javax.sdp.SessionDescription;
import javax.sip.ClientTransaction;
import javax.sip.Dialog;
import javax.sip.DialogState;
import javax.sip.InvalidArgumentException;
import javax.sip.ListeningPoint;
import javax.sip.RequestEvent;
import javax.sip.ServerTransaction;
import javax.sip.SipException;
import javax.sip.SipProvider;
import javax.sip.TransactionState;
import javax.sip.address.Address;
import javax.sip.address.SipURI;
import javax.sip.header.AcceptHeader;
import javax.sip.header.CSeqHeader;
import javax.sip.header.CallIdHeader;
import javax.sip.header.ContactHeader;
import javax.sip.header.ContentTypeHeader;
import javax.sip.header.ExtensionHeader;
import javax.sip.header.FromHeader;
import javax.sip.header.Header;
import javax.sip.header.MaxForwardsHeader;
import javax.sip.header.ReferToHeader;
import javax.sip.header.RouteHeader;
import javax.sip.header.SupportedHeader;
import javax.sip.header.ToHeader;
import javax.sip.header.ViaHeader;
import javax.sip.header.WarningHeader;
import javax.sip.message.Request;
import javax.sip.message.Response;

import org.apache.log4j.Logger;
import org.sipfoundry.sipxbridge.symmitron.BridgeInterface;
import org.sipfoundry.sipxbridge.symmitron.BridgeState;
import org.sipfoundry.sipxbridge.symmitron.KeepaliveMethod;
import org.sipfoundry.sipxbridge.symmitron.SymImpl;
import org.sipfoundry.sipxbridge.symmitron.SymmitronClient;

/**
 * A class that represents an ongoing call. Each call Id points at one of these structures. It can
 * be a many to one mapping. When we receive an incoming request we retrieve the corresponding
 * backtobackuseragent and route the request to it. It keeps the necessary state to handle
 * subsequent requests that are related to this call.
 * 
 * @author M. Ranganathan
 * 
 */
public class BackToBackUserAgent {

    /*
     * The ITSP account for outbound calls.
     */
    private ItspAccountInfo itspAccountInfo;

    private RtpBridge rtpBridge;

    /*
     * This is just a table of dialogs that reference this B2bua. When the table is empty the
     * b2bua is GCd.
     */
    HashSet<Dialog> dialogTable = new HashSet<Dialog>();

    /*
     * The REFER dialog currently in progress.
     */
    Dialog referingDialog;

    private Dialog referingDialogPeer;

    private int counter;

    /*
     * Any call Id associated with this b2bua will be derived from this base call id.
     */
    private String creatingCallId;

    /*
     * The Dialog that created this B2BUA.
     */
    private Dialog creatingDialog;

    private static Logger logger = Logger.getLogger(BackToBackUserAgent.class);

    private static final String ORIGINATOR = "originator";

    private static final String SIPXBRIDGE = "sipxbridge";
    
    private static final String SIPXBRIDGE_REPLACES = "X-SIPXBRIDGE-REPLACES";

    private SymmitronClient symmitronClient;

    private String symmitronServerHandle;

    private Dialog musicOnHoldDialog;

    // ///////////////////////////////////////////////////////////////////////
    // Private methods.
    // ///////////////////////////////////////////////////////////////////////

    // ////////////////////////////////////////////////////////////////////////
    // Package local methods.
    // ////////////////////////////////////////////////////////////////////////

    /**
     * Create a dialog to dialog association.
     * 
     * @param dialog1 - first dialog.
     * @param dialog2 - second dialog.
     * 
     */
    void pairDialogs(Dialog dialog1, Dialog dialog2) {
        DialogApplicationData dad1 = DialogApplicationData.get(dialog1);

        DialogApplicationData dad2 = DialogApplicationData.get(dialog2);

        dad1.peerDialog = dialog2;
        dad2.peerDialog = dialog1;
    }

    /**
     * Retrieve a Sym for the Lan RTP session.
     * 
     * @param dialog
     * @param codecFilter
     * @return the Lan facing Sym.
     * 
     * @throws IOException
     */
    RtpSession getLanRtpSession(Dialog dialog) throws IOException {

        DialogApplicationData dialogApplicationData = DialogApplicationData.get(dialog);

        if (dialogApplicationData.getRtpSession() == null) {
            SymImpl symImpl = symmitronClient.createEvenSym();
            RtpSession rtpSession = new RtpSession(symImpl);

            // RtpReceiverEndpoint endpoint = new RtpReceiverEndpoint(symImpl.getReceiver());

            dialogApplicationData.setRtpSession(rtpSession);
            /*
             * SessionDescription sd = SdpFactory.getInstance().createSessionDescription(
             * this.rtpBridge.sessionDescription.toString());
             * rtpSession.getReceiver().setSessionDescription(
             * SipUtilities.cleanSessionDescription(sd, Gateway.getCodecName()));
             */

            this.rtpBridge.addSym(rtpSession);

        }
        logger.debug("getLanRtpSession : " + dialog + " rtpSession = "
                + dialogApplicationData.getRtpSession());

        return dialogApplicationData.getRtpSession();

    }

    /**
     * RTP session that is connected to the WAN Side.
     * 
     * @return
     */
    RtpSession getWanRtpSession(Dialog dialog) {
        try {
            RtpSession rtpSession = DialogApplicationData.getRtpSession(dialog);
            if (rtpSession == null) {

                SymImpl symImpl = symmitronClient.createEvenSym();
                rtpSession = new RtpSession(symImpl);
                /*
                 * RtpReceiverEndpoint mediaEndpoint = new
                 * RtpReceiverEndpoint(symImpl.getReceiver()); mediaEndpoint
                 */
                String ipAddress = (itspAccountInfo == null || itspAccountInfo
                        .isGlobalAddressingUsed()) ? symmitronClient.getPublicAddress()
                        : symmitronClient.getExternalAddress();

                SessionDescription sd = SdpFactory.getInstance().createSessionDescription(
                        this.rtpBridge.sessionDescription.toString());
                String codecName = Gateway.getCodecName();
                rtpSession.getReceiver().setIpAddress(ipAddress);
                rtpSession.getReceiver().setSessionDescription(
                        SipUtilities.cleanSessionDescription(sd, codecName));
                DialogApplicationData.get(dialog).setRtpSession(rtpSession);
                this.rtpBridge.addSym(rtpSession);

            }
            logger.debug("getWanRtpSession : " + dialog + " rtpSession = " + rtpSession);
            return rtpSession;
        } catch (SdpParseException ex) {
            throw new RuntimeException("Unexpected exception -- FIXME", ex);
        }

    }

    /**
     * This method handles an Invite with a replaces header in it. It is invoked for consultative
     * transfers. It does a Dialog splicing and media splicing on the Refer dialog. Here is the
     * reference call flow for this case:
     * 
     * <pre>
     * 
     *  Transferor           Transferee             Transfer
     *              |                    |                  Target
     *              |                    |                    |
     *    dialog1   | INVITE/200 OK/ACK F1 F2                 |
     *             	|&lt;-------------------|                    |
     *    dialog1   | INVITE (hold)/200 OK/ACK                |
     *              |-------------------&gt;|                    |
     *    dialog2   | INVITE/200 OK/ACK F3 F4                 |
     *              |----------------------------------------&gt;|
     *    dialog2   | INVITE (hold)/200 OK/ACK                |
     *              |----------------------------------------&gt;|
     *    dialog3   | REFER (Target-Dialog:2,                 |
     *              |  Refer-To:sips:Transferee?Replaces=1) F5|
     *              |----------------------------------------&gt;|
     *    dialog3   | 202 Accepted       |                    |
     *              |&lt;----------------------------------------|
     *    dialog3   | NOTIFY (100 Trying)|                    |
     *              |&lt;----------------------------------------|
     *    dialog3   |                    |            200 OK  |
     *              |----------------------------------------&gt;|
     *    dialog4   |         INVITE (Replaces:dialog1)/200 OK/ACK F6
     *              |                    |&lt;-------------------|
     *    dialog1   | BYE/200 OK         |                    |
     *              |&lt;-------------------|                    |
     *    dialog3   | NOTIFY (200 OK)    |                    |
     *              |&lt;----------------------------------------|
     *    dialog3   |                    |            200 OK  |
     *              |----------------------------------------&gt;|
     *    dialog2   | BYE/200 OK         |                    |
     *              |----------------------------------------&gt;|
     *              |              (transferee and target converse)
     *    dialog4   |                    |  BYE/200 OK        |
     *              |                    |-------------------&gt;|
     * </pre>
     * 
     * 
     * @param serverTransaction
     * @param toDomain
     * @param isphone
     * @throws SipException
     * @throws InvalidArgumentException
     */
    void handleSpriralInviteWithReplaces(RequestEvent requestEvent, Dialog replacedDialog,
            ServerTransaction serverTransaction, String toDomain, boolean isphone)
            throws SipException {
        /* The inbound INVITE */
        Request incomingRequest = serverTransaction.getRequest();
        SipProvider provider = (SipProvider) requestEvent.getSource();
        try {
            Dialog replacedDialogPeerDialog = ((DialogApplicationData) replacedDialog
                    .getApplicationData()).peerDialog;
            if (logger.isDebugEnabled()) {
                logger
                        .debug("replacedDialogPeerDialog = "
                                + ((DialogApplicationData) replacedDialog.getApplicationData()).peerDialog);
                logger.debug("referingDialogPeerDialog = " + this.referingDialogPeer);

            }

            if (replacedDialogPeerDialog.getState() == DialogState.TERMINATED) {
                Response response = ProtocolObjects.messageFactory.createResponse(
                        Response.BUSY_HERE, incomingRequest);
                SupportedHeader sh = ProtocolObjects.headerFactory
                        .createSupportedHeader("replaces");
                response.setHeader(sh);
                serverTransaction.sendResponse(response);
                return;
            }

            if (this.referingDialogPeer.getState() == DialogState.TERMINATED) {
                Response response = ProtocolObjects.messageFactory.createResponse(
                        Response.BUSY_HERE, incomingRequest);
                serverTransaction.sendResponse(response);
                return;
            }

            this.pairDialogs(
                    ((DialogApplicationData) replacedDialog.getApplicationData()).peerDialog,
                    this.referingDialogPeer);
            /*
             * Tear down the Music On Hold Dialog if any.
             */
            this.sendByeToMohServer();

            /* The replaced dialog is about ready to die so he has no peer */
            ((DialogApplicationData) replacedDialog.getApplicationData()).peerDialog = null;

            if (logger.isDebugEnabled()) {
                logger.debug("referingDialog = " + referingDialog);
                logger.debug("replacedDialog = " + replacedDialog);

                DialogApplicationData dat = (DialogApplicationData) replacedDialog
                        .getApplicationData();

            }

            /*
             * We need to form a new bridge. Remove the refering dialog from our rtp bridge.
             * Remove the replacedDialog from its rtpBridge and form a new bridge.
             */
            rtpBridge.pause();

            Set<RtpSession> myrtpSessions = this.rtpBridge.getSyms();
            DialogApplicationData replacedDialogApplicationData = (DialogApplicationData) replacedDialog
                    .getApplicationData();
            RtpBridge hisBridge = replacedDialogApplicationData.getBackToBackUserAgent().rtpBridge;
            hisBridge.pause();

            Set<RtpSession> hisRtpSessions = hisBridge.getSyms();
            BridgeInterface bridge = symmitronClient.createBridge();

            RtpBridge newBridge = new RtpBridge(bridge);

            for (Iterator<RtpSession> it = myrtpSessions.iterator(); it.hasNext();) {
                RtpSession sym = it.next();
                if (sym != DialogApplicationData.getRtpSession(replacedDialog)
                        && sym != DialogApplicationData.getRtpSession(referingDialog)) {
                    newBridge.addSym(sym);
                    it.remove();
                }
            }

            for (Iterator<RtpSession> it = hisRtpSessions.iterator(); it.hasNext();) {
                RtpSession sym = it.next();
                if (sym != DialogApplicationData.getRtpSession(replacedDialog)
                        && sym != DialogApplicationData.getRtpSession(referingDialog)) {
                    newBridge.addSym(sym);
                    it.remove();
                }
            }

            /*
             * Let the RTP Bridge initialize its selector table.
             */

            this.rtpBridge.resume();

            hisBridge.resume();

            newBridge.start();

            if (logger.isDebugEnabled()) {
                logger.debug("replacedDialog State " + replacedDialog.getState());
            }

            if (replacedDialog.getState() != DialogState.TERMINATED
                    && replacedDialog.getState() != DialogState.EARLY) {
                Request byeRequest = replacedDialog.createRequest(Request.BYE);
                ClientTransaction byeCtx = ((DialogExt) replacedDialog).getSipProvider()
                        .getNewClientTransaction(byeRequest);
                TransactionApplicationData.attach(byeCtx,
                        Operation.HANDLE_SPIRAL_INVITE_WITH_REPLACES);
                replacedDialog.sendRequest(byeCtx);

            }

            Response response = ProtocolObjects.messageFactory.createResponse(Response.OK,
                    incomingRequest);
            SupportedHeader sh = ProtocolObjects.headerFactory.createSupportedHeader("replaces");
            response.setHeader(sh);

            ContactHeader contactHeader = SipUtilities.createContactHeader(
                    Gateway.SIPXBRIDGE_USER, provider);
            response.setHeader(contactHeader);

            /*
             * If re-INVITE is supported send the INVITE down the other leg. Note that at this
             * point we have already queried the Sdp from the other leg. We will ACK that with the
             * response received.
             */

            if (Gateway.getAccountManager().getBridgeConfiguration().isReInviteSupported()) {
                Dialog referingDialogPeer = this.referingDialogPeer;

                DialogApplicationData referingDialogPeerApplicationData = DialogApplicationData
                        .get(referingDialogPeer);
                Response lastResponse = referingDialogPeerApplicationData.lastResponse;
                DialogApplicationData replacedDialogPeerDialogApplicationData = DialogApplicationData
                        .get(replacedDialogPeerDialog);
                Request reInvite = replacedDialogPeerDialog.createRequest(Request.INVITE);

                ItspAccountInfo accountInfo = replacedDialogPeerDialogApplicationData
                        .getItspInfo();
                if ((itspAccountInfo == null && Gateway.getGlobalAddress() != null)
                        || (accountInfo != null && accountInfo.isGlobalAddressingUsed())) {
                    SipUtilities.setGlobalAddresses(reInvite);
                    replacedDialogPeerDialogApplicationData.getRtpSession().getReceiver()
                            .setIpAddress(Gateway.getGlobalAddress());
                }

                SessionDescription sessionDescription = SipUtilities
                        .getSessionDescription(lastResponse);

                replacedDialogPeerDialogApplicationData.getRtpSession().getReceiver()
                        .setSessionDescription(sessionDescription);

                SipUtilities.incrementSessionVersion(sessionDescription);

                reInvite.setContent(sessionDescription.toString(), ProtocolObjects.headerFactory
                        .createContentTypeHeader("application", "sdp"));
                SipProvider wanProvider = ((DialogExt) replacedDialogPeerDialog).getSipProvider();
                ClientTransaction ctx = wanProvider.getNewClientTransaction(reInvite);
                TransactionApplicationData.attach(ctx,
                        Operation.HANDLE_SPIRAL_INVITE_WITH_REPLACES);
                replacedDialogPeerDialog.sendRequest(ctx);
            }
            serverTransaction.sendResponse(response);

        } catch (Exception ex) {
            logger.error("Unexpected internal error occured", ex);

            CallControlManager.sendBadRequestError(serverTransaction, ex);
        }

    }

    private BackToBackUserAgent(Request request, ItspAccountInfo itspAccountInfo)
            throws IOException {

        this.itspAccountInfo = itspAccountInfo;

    }

    BackToBackUserAgent(SipProvider provider, Request request, Dialog dialog,
            ItspAccountInfo itspAccountInfo) throws IOException {
        this(request, itspAccountInfo);
        // Received this reuqest from the LAN
        // The symmitron to use is the symmitron that runs where the request
        // originated from.
        if (provider == Gateway.getLanProvider()) {
            String address = ((ViaHeader) request.getHeader(ViaHeader.NAME)).getHost();
            this.symmitronClient = Gateway.getSymmitronClient(address);
        } else {
            this.symmitronClient = Gateway.getSymmitronClient(Gateway.getLocalAddress());
        }

        BridgeInterface bridge = symmitronClient.createBridge();
        this.symmitronServerHandle = symmitronClient.getServerHandle();
        rtpBridge = new RtpBridge(request, bridge);
        dialogTable.add(dialog);
        this.creatingDialog = dialog;
        this.creatingCallId = ((CallIdHeader) request.getHeader(CallIdHeader.NAME)).getCallId();

    }

    /**
     * Remove a dialog from the table ( the dialog terminates ).
     */
    void removeDialog(Dialog dialog) {

        this.dialogTable.remove(dialog);

        int count = 0;

        for (Dialog d : dialogTable) {
            DialogApplicationData dat = DialogApplicationData.get(d);
            if (!dat.isOriginatedBySipxbridge)
                count++;
        }

        if (count == 0) {
            for (Dialog d : dialogTable) {
                d.delete();
            }

            this.rtpBridge.stop();

            dialogTable.clear();

        }
        if (logger.isDebugEnabled()) {
            logger.debug("Remove Dialog " + dialog);
            logger.debug("Dialog table size = " + this.dialogTable.size());
        }

        if (dialogTable.size() == 0) {

            this.sendByeToMohServer();

            Gateway.getCallControlManager().removeBackToBackUserAgent(this);
        }
    }

    /**
     * Get the itsp account info.
     * 
     * @return the itsp account info.
     */
    ItspAccountInfo getItspAccountInfo() {
        return itspAccountInfo;
    }

    /**
     * Add a dialog entry to the b2bua.
     * 
     * @param provider
     * @param dialog
     */
    void addDialog(Dialog dialog) {
        this.dialogTable.add(dialog);

    }

    /**
     * Create an INVITE request from an inbound REFER.
     * 
     * @param requestEvent -- the inbound REFER request Event
     * @return the INVITE request crafted from the IB Refer
     * 
     */
    Request createInviteFromReferRequest(RequestEvent requestEvent) throws SipException,
            ParseException, IOException {
        Dialog dialog = requestEvent.getDialog();
        ServerTransaction referServerTransaction = requestEvent.getServerTransaction();
        Request referRequest = referServerTransaction.getRequest();

         Request newRequest = dialog.createRequest(Request.INVITE);

        /* Requesting new dialog -- remove the route header */
        newRequest.removeHeader(RouteHeader.NAME);

        
        /*
         * Get the Refer-To header and convert it into an INVITE to send to the REFER target.
         */

        ReferToHeader referToHeader = (ReferToHeader) referRequest.getHeader(ReferToHeader.NAME);
        SipURI uri = (SipURI) referToHeader.getAddress().getURI();
    
        /* Does the refer to header contain a Replaces? ( attended transfer ) */
        String replacesParam = uri.getHeader(ReplacesHeader.NAME);
        
          
        ReplacesHeader replacesHeader = null;
        
        
        if (replacesParam != null) {

            String decodedReplaces = URLDecoder.decode(replacesParam, "UTF-8");
            replacesHeader =  (ReplacesHeader) ProtocolObjects.headerFactory.createHeader(
                    ReplacesHeader.NAME, decodedReplaces);
        }
        
        uri.removeParameter("Replaces");

        uri.removePort(); 
        
        if (replacesHeader != null) {
            newRequest.addHeader(replacesHeader);
        }

         
        
        for (Iterator it =  uri.getHeaderNames(); it.hasNext();) {
            String headerName = (String) it.next();
            String headerValue = uri.getHeader(headerName);
            Header header = null;
            if (headerValue != null) {

                String decodedHeaderValue = URLDecoder.decode(headerValue, "UTF-8");
                header = (Header) ProtocolObjects.headerFactory.createHeader(headerName,
                        decodedHeaderValue);
            }
            if (header != null) {
                newRequest.addHeader(header);
            }
        }
        
        /*
         * Remove any header parameters - we have already dealt with them above.
         */
        ((gov.nist.javax.sip.address.SipURIExt) uri).removeHeaders();


        newRequest.setRequestURI(uri);

        if (referRequest.getHeader(ReferredByHeader.NAME) != null) {
            newRequest.setHeader(referRequest.getHeader(ReferredByHeader.NAME));
        }

        CallIdHeader callId = ProtocolObjects.headerFactory
                .createCallIdHeader(this.creatingCallId + "." + this.counter++);
        newRequest.setHeader(callId);
        Gateway.getCallControlManager().setBackToBackUserAgent(callId.getCallId(), this);

        SipUtilities.addAllowHeaders(newRequest);

        SupportedHeader sh = ProtocolObjects.headerFactory.createSupportedHeader("replaces");
        newRequest.setHeader(sh);

        ContactHeader contactHeader = SipUtilities.createContactHeader(null, Gateway
                .getLanProvider());
        newRequest.setHeader(contactHeader);
        /*
         * Create a new out of dialog request.
         */
        ToHeader toHeader = (ToHeader) newRequest.getHeader(ToHeader.NAME).clone();
        toHeader.removeParameter("tag");
        toHeader.getAddress().setURI(uri);
        newRequest.setHeader(toHeader);

        FromHeader fromHeader = (FromHeader) newRequest.getHeader(FromHeader.NAME).clone();
        fromHeader.setTag(Integer.toString(Math.abs(new Random().nextInt())));
        ContentTypeHeader cth = ProtocolObjects.headerFactory.createContentTypeHeader(
                "application", "sdp");
        newRequest.setHeader(fromHeader);

        RtpSession lanRtpSession = this.getLanRtpSession(dialog);
        SessionDescription sd = lanRtpSession.getReceiver().getSessionDescription();
        SipUtilities.setDuplexity(sd, "sendrecv");
        newRequest.setContent(sd, cth);

        return newRequest;

    }

    /**
     * This method is called when the REFER is received at the B2BUA. We need to redirect the
     * INVITE to the contact mentioned in the Refer. To determine the codec that was negotiated in
     * the original Call Setup, we send an INVITE (no-sdp) to the dialog to solicit an offer. This
     * operation has already returned a result when this method is called.
     * 
     * 
     * @param inviteRequest -- the INVITE request.
     * @param referRequestEvent -- the REFER dialog
     * @param sessionDescription -- the session description to use when forwarding the INVITE to
     *        the sipx proxy server.
     * 
     */
    void referInviteToSipxProxy(Request inviteRequest, RequestEvent referRequestEvent,
            SessionDescription sessionDescription) {
        logger.debug("referInviteToSipxProxy: " + this);

        try {

            Dialog dialog = referRequestEvent.getDialog();
            Request referRequest = referRequestEvent.getRequest();
            ServerTransaction stx = referRequestEvent.getServerTransaction();

            /*
             * Send an ACCEPTED
             */

            if (stx.getState() != TransactionState.TERMINATED) {
                Response response = ProtocolObjects.messageFactory.createResponse(
                        Response.ACCEPTED, referRequest);
                response.setHeader(SipUtilities.createContactHeader(null,
                        ((SipProvider) referRequestEvent.getSource())));
                stx.sendResponse(response);
                DialogApplicationData.get(dialog).isReferAccepted = true;
                DialogApplicationData.get(dialog).referRequest = referRequest;

            }

            /*
             * Create a new client transaction. First attach any queried session
             * description.
             */
            if ( sessionDescription  != null ) {
            	SipUtilities.setSessionDescription(inviteRequest, sessionDescription);
            }
            ClientTransaction ct = Gateway.getLanProvider()
                    .getNewClientTransaction(inviteRequest);

            DialogApplicationData newDialogApplicationData = DialogApplicationData.attach(this,
                    ct.getDialog(), ct, ct.getRequest());
            DialogApplicationData dialogApplicationData = (DialogApplicationData) dialog
                    .getApplicationData();

            newDialogApplicationData.rtpSession = dialogApplicationData.rtpSession;
            newDialogApplicationData.peerDialog = dialogApplicationData.peerDialog;

            /*
             * This sends the BYE early to the MOH dialog. if
             * (dialogApplicationData.musicOnHoldDialog != null) {
             * this.sendByeToMohServer(dialogApplicationData.musicOnHoldDialog);
             * dialogApplicationData.musicOnHoldDialog = null; }
             */

            if (logger.isDebugEnabled()) {
                logger.debug("referInviteToSipxProxy peerDialog = "
                        + newDialogApplicationData.peerDialog);

            }

            this.referingDialogPeer = dialogApplicationData.peerDialog;

            DialogApplicationData dat = (DialogApplicationData) this.referingDialogPeer
                    .getApplicationData();
            dat.peerDialog = ct.getDialog();

            /*
             * We terminate the dialog. There is no peer.
             */
            dialogApplicationData.peerDialog = null;

            /*
             * Mark that we (sipxbridge) originated the dialog.
             */
            newDialogApplicationData.isOriginatedBySipxbridge = true;

            /*
             * Record the referDialog so that when responses for the Client transaction come in we
             * can NOTIFY the referrer.
             */
            this.referingDialog = dialog;

            ct.getDialog().setApplicationData(newDialogApplicationData);
            TransactionApplicationData tad = TransactionApplicationData.attach(ct,
                    Operation.REFER_INVITE_TO_SIPX_PROXY);
            tad.backToBackUa = ((DialogApplicationData) dialog.getApplicationData())
                    .getBackToBackUserAgent();
            tad.referingDialog = dialog;

            tad.referRequest = referRequest;

            /*
             * Stamp the via header with our stamp so that we know we Referred this request. we
             * will use this for spiral detection.
             */
            ViaHeader via = (ViaHeader) inviteRequest.getHeader(ViaHeader.NAME);
            /*
             * This is our signal that we originated the redirection. We use this in the INVITE
             * processing below.
             */
            via.setParameter(ORIGINATOR, SIPXBRIDGE);

            ct.sendRequest();

        } catch (ParseException ex) {
            logger.error("Unexpected parse exception", ex);
            throw new RuntimeException("Unexpected parse exception", ex);

        } catch (Exception ex) {
            logger.error("Error while processing the request - hanging up ", ex);
            try {
                this.tearDown();
            } catch (Exception e) {
                logger.error("Unexpected exception tearing down session", e);
            }
        }

    }

    /**
     * Forward the REFER request to the ITSP.
     * 
     * 
     * @param requestEvent - INBOUND REFER ( from sipx )
     */
    void forwardReferToItsp(RequestEvent requestEvent) {

        try {
            Dialog dialog = requestEvent.getDialog();
            DialogApplicationData dat = DialogApplicationData.get(dialog);
            Dialog peerDialog = DialogApplicationData.getPeerDialog(dialog);
            Request referRequest = requestEvent.getRequest();
            ItspAccountInfo itspInfo = DialogApplicationData.get(peerDialog).getItspInfo();
            SipProvider wanProvider = ((DialogExt) peerDialog).getSipProvider();

            Request outboundRefer = peerDialog.createRequest(Request.REFER);

            ReferToHeader referToHeader = (ReferToHeader) referRequest
                    .getHeader(ReferToHeader.NAME);
            SipURI uri = (SipURI) referToHeader.getAddress().getURI();
            String referToUserName = uri.getUser();

            SipURI forwardedReferToUri = SipUtilities.createInboundRequestUri(itspInfo);

            forwardedReferToUri.setParameter("target", referToUserName);

            Address referToAddress = ProtocolObjects.addressFactory
                    .createAddress(forwardedReferToUri);

            SipURI forwardedReferredByUri = SipUtilities.createInboundReferredByUri(itspInfo);

            Address referByAddress = ProtocolObjects.addressFactory
                    .createAddress(forwardedReferredByUri);

            ReferToHeader outboundReferToHeader = ProtocolObjects.headerFactory
                    .createReferToHeader(referToAddress);

            ReferredByHeader outboundReferByHeader = ((HeaderFactoryExt) ProtocolObjects.headerFactory)
                    .createReferredByHeader(referByAddress);

            outboundRefer.setHeader(outboundReferByHeader);

            outboundRefer.setHeader(outboundReferToHeader);

            if (itspInfo == null || itspInfo.isGlobalAddressingUsed()) {
                SipUtilities.setGlobalAddresses(outboundRefer);
            }

            SipUtilities.addWanAllowHeaders(outboundRefer);

            ClientTransaction outboundReferClientTx = wanProvider
                    .getNewClientTransaction(outboundRefer);
            TransactionApplicationData tad = TransactionApplicationData.attach(
                    outboundReferClientTx, Operation.FORWARD_REFER);
            tad.setServerTransaction(requestEvent.getServerTransaction());
            tad.serverTransactionProvider = (SipProvider) requestEvent.getSource();
            peerDialog.sendRequest(outboundReferClientTx);

        } catch (SipException ex) {
            logger.error("Error while processing the request - hanging up ", ex);
            try {
                this.tearDown();
            } catch (Exception e) {
                logger.error("Unexpected exception tearing down session", e);
            }
        } catch (ParseException e) {
            logger.error("INTERNAL Error while processing the request - hanging up ", e);
            throw new RuntimeException("INTERNAL Error while processing the request ", e);
        }
    }

    /**
     * This method sends an INVITE to SIPX proxy server.
     * 
     * @param requestEvent -- The incoming RequestEvent ( from the ITSP side ) for which we are
     *        generating the request outbound to the sipx proxy server.
     * 
     * @param serverTransaction -- The SIP Server transaction that we created to service this
     *        request.
     * @param itspAccountInfo -- the ITSP account that is sending this inbound request.
     */

    void sendInviteToSipxProxy(RequestEvent requestEvent, ServerTransaction serverTransaction) {
        Request request = requestEvent.getRequest();

        try {
            /*
             * This is a request I got from the external provider. Route this into the network.
             * The SipURI is the sipx proxy URI. He takes care of the rest.
             */

            SipURI incomingRequestURI = (SipURI) request.getRequestURI();
            Dialog inboundDialog = serverTransaction.getDialog();

            SipURI uri = null;
            if (!Gateway.isInboundCallsRoutedToAutoAttendant()) {
                uri = ProtocolObjects.addressFactory.createSipURI(incomingRequestURI.getUser(),
                        Gateway.getSipxProxyDomain());
            } else {
                String destination = Gateway.getAutoAttendantName();
                if (destination.indexOf("@") == -1) {
                    uri = ProtocolObjects.addressFactory.createSipURI(destination, Gateway
                            .getSipxProxyDomain());
                    if (Gateway.getBridgeConfiguration().getSipxProxyPort() != -1) {
                        uri.setPort(Gateway.getBridgeConfiguration().getSipxProxyPort());
                    }
                } else {
                    logger.warn("routing to domain other than proxy domain!");
                    uri = (SipURI) ProtocolObjects.addressFactory.createURI("sip:" + destination);
                }
            }

            CallIdHeader callIdHeader = ProtocolObjects.headerFactory
                    .createCallIdHeader(this.creatingCallId + "." + counter++);

            Gateway.getCallControlManager()
                    .setBackToBackUserAgent(callIdHeader.getCallId(), this);

            CSeqHeader cseqHeader = ProtocolObjects.headerFactory.createCSeqHeader(1L,
                    Request.INVITE);

            FromHeader fromHeader = (FromHeader) request.getHeader(FromHeader.NAME).clone();

            fromHeader.setParameter("tag", Long.toString(Math.abs(new Random().nextLong())));

            /*
             * Change the domain of the inbound request to that of the sipx proxy. Change the user
             * part if routed to specific extension.
             */
            String autoAttendantName = Gateway.getAutoAttendantName();
            ToHeader toHeader = null;
            if (autoAttendantName != null && autoAttendantName.indexOf("@") != -1) {
                SipURI toUri = (SipURI) ProtocolObjects.addressFactory.createURI("sip:"
                        + autoAttendantName);
                Address toAddress = ProtocolObjects.addressFactory.createAddress(toUri);
                toHeader = ProtocolObjects.headerFactory.createToHeader(toAddress, null);
            } else {
                toHeader = (ToHeader) request.getHeader(ToHeader.NAME).clone();
                ((SipURI) toHeader.getAddress().getURI()).setHost(Gateway.getSipxProxyDomain());
                ((SipURI) toHeader.getAddress().getURI()).removePort();
                if (Gateway.isInboundCallsRoutedToAutoAttendant()) {
                    ((SipURI) toHeader.getAddress().getURI()).setUser(Gateway
                            .getAutoAttendantName());
                }
                toHeader.removeParameter("tag");
            }

            ViaHeader viaHeader = SipUtilities.createViaHeader(Gateway.getLanProvider(), Gateway
                    .getSipxProxyTransport());
            viaHeader.setParameter(ORIGINATOR, SIPXBRIDGE);

            List<ViaHeader> viaList = new LinkedList<ViaHeader>();

            viaList.add(viaHeader);

            MaxForwardsHeader maxForwards = (MaxForwardsHeader) request
                    .getHeader(MaxForwardsHeader.NAME);

            maxForwards.decrementMaxForwards();
            Request newRequest = ProtocolObjects.messageFactory.createRequest(uri,
                    Request.INVITE, callIdHeader, cseqHeader, fromHeader, toHeader, viaList,
                    maxForwards);
            ContactHeader contactHeader = SipUtilities.createContactHeader(incomingRequestURI
                    .getUser(), Gateway.getLanProvider());
            newRequest.setHeader(contactHeader);
            /*
             * The incoming session description.
             */
            SessionDescription sessionDescription = SipUtilities.getSessionDescription(request);

            /*
             * Are we restricting codecs?
             */
            if (Gateway.getCodecName() != null) {
                SessionDescription newSd = SipUtilities.cleanSessionDescription(
                        sessionDescription, Gateway.getCodecName());

                if (newSd == null) {
                    Response response = ProtocolObjects.messageFactory.createResponse(
                            Response.NOT_ACCEPTABLE_HERE, request);
                    response.setReasonPhrase("Only " + Gateway.getCodecName() + " supported ");
                    serverTransaction.sendResponse(response);
                    return;

                }
                logger.debug("Clean session description = " + sessionDescription);
            }
            RtpSession incomingSession = this.getWanRtpSession(inboundDialog);

            RtpTransmitterEndpoint rtpEndpoint = new RtpTransmitterEndpoint(incomingSession,
                    symmitronClient);
            incomingSession.setTransmitter(rtpEndpoint);
            KeepaliveMethod keepaliveMethod = this.itspAccountInfo != null ? this.itspAccountInfo
                    .getRtpKeepaliveMethod() : KeepaliveMethod.NONE;

            rtpEndpoint.setKeepAliveMethod(keepaliveMethod);
            rtpEndpoint.setSessionDescription(sessionDescription);

            ContentTypeHeader cth = ProtocolObjects.headerFactory.createContentTypeHeader(
                    "application", "sdp");
            /*
             * Create a new client transaction.
             */
            ClientTransaction ct = Gateway.getLanProvider().getNewClientTransaction(newRequest);

            Dialog outboundDialog = ct.getDialog();

            DialogApplicationData.attach(this, outboundDialog, ct, ct.getRequest());
            /*
             * Set the ITSP account info for the inbound INVITE to sipx proxy.
             */
            DialogApplicationData.get(outboundDialog).setItspInfo(itspAccountInfo);
            pairDialogs(inboundDialog, outboundDialog);
            
            /*
             * Apply the Session Description from the INBOUND invite to the
             * Receiver of the RTP session pointing towards the PBX side.
             * This resets the ports in the session description.
             */
            SessionDescription sd = SipUtilities.getSessionDescription(request);
           
            this.getLanRtpSession(outboundDialog).getReceiver().setSessionDescription(sd);
            
           
            newRequest.setContent(this.getLanRtpSession(outboundDialog).getReceiver()
                    .getSessionDescription().toString(), cth);
           

            SipUtilities.addAllowHeaders(newRequest);
            newRequest.setHeader(ProtocolObjects.headerFactory.createSupportedHeader("replaces"));

            TransactionApplicationData tad = new TransactionApplicationData(ct,
                    Operation.SEND_INVITE_TO_SIPX_PROXY);

            SipProvider provider = (SipProvider) requestEvent.getSource();

            tad.clientTransactionProvider = Gateway.getLanProvider();
            tad.incomingSession = incomingSession;
            tad.outgoingSession = this.getLanRtpSession(outboundDialog);
            tad.setServerTransaction(serverTransaction);
            tad.serverTransactionProvider = provider;
            tad.backToBackUa = this;
            tad.itspAccountInfo = itspAccountInfo;

            this.addDialog(ct.getDialog());
            this.referingDialog = ct.getDialog();

            this.referingDialogPeer = serverTransaction.getDialog();

            ct.sendRequest();

        } catch (InvalidArgumentException ex) {
            logger.error("Unexpected exception encountered");
            throw new RuntimeException("Unexpected exception encountered", ex);
        } catch (ParseException ex) {
            logger.error("Unexpected parse exception", ex);
            throw new RuntimeException("Unexpected parse exception", ex);
        } catch (SdpParseException ex) {
            try {
                Response response = ProtocolObjects.messageFactory.createResponse(
                        Response.BAD_REQUEST, request);
                serverTransaction.sendResponse(response);
            } catch (Exception e) {
                logger.error("Unexpected exception", e);
            }

        } catch (IOException ex) {
            for (Dialog dialog : this.dialogTable) {
                dialog.delete();
            }
        } catch (Exception ex) {
            logger.error("Error while processing the request", ex);
            try {
                Response response = ProtocolObjects.messageFactory.createResponse(
                        Response.SERVICE_UNAVAILABLE, request);
                serverTransaction.sendResponse(response);
            } catch (Exception e) {
                logger.error("Unexpected exception ", e);
            }
        }

    }

    /**
     * Send an INVITE to the MOH server on SIPX.
     * 
     * @return the dialog generated as a result of sending the invite to the MOH server.
     * 
     */
    ClientTransaction createClientTxToMohServer(SessionDescription sessionDescription) {

        ClientTransaction retval = null;

        try {

            SipURI uri = Gateway.getMusicOnHoldUri();
            SipProvider lanProvider = Gateway.getLanProvider();

            CallIdHeader callIdHeader = ProtocolObjects.headerFactory
                    .createCallIdHeader(this.creatingCallId + "." + this.counter++);

            Gateway.getCallControlManager()
                    .setBackToBackUserAgent(callIdHeader.getCallId(), this);

            CSeqHeader cseqHeader = ProtocolObjects.headerFactory.createCSeqHeader(1L,
                    Request.INVITE);

            Address gatewayAddress = Gateway.getGatewayFromAddress();

            FromHeader fromHeader = ProtocolObjects.headerFactory.createFromHeader(
                    gatewayAddress, Long.toString(Math.abs(new Random().nextLong())));

            Address mohServerAddress = Gateway.getMusicOnHoldAddress();

            ToHeader toHeader = ProtocolObjects.headerFactory.createToHeader(mohServerAddress,
                    null);

            toHeader.removeParameter("tag");

            ViaHeader viaHeader = SipUtilities.createViaHeader(Gateway.getLanProvider(), Gateway
                    .getSipxProxyTransport());

            List<ViaHeader> viaList = new LinkedList<ViaHeader>();

            viaList.add(viaHeader);

            MaxForwardsHeader maxForwards = ProtocolObjects.headerFactory
                    .createMaxForwardsHeader(20);

            maxForwards.decrementMaxForwards();
            Request newRequest = ProtocolObjects.messageFactory.createRequest(uri,
                    Request.INVITE, callIdHeader, cseqHeader, fromHeader, toHeader, viaList,
                    maxForwards);
            ContactHeader contactHeader = SipUtilities.createContactHeader(
                    Gateway.SIPXBRIDGE_USER, Gateway.getLanProvider());
            newRequest.setHeader(contactHeader);

            /*
             * Create a new client transaction.
             */
            ClientTransaction ct = Gateway.getLanProvider().getNewClientTransaction(newRequest);

            MediaDescription mediaDescription = (MediaDescription) sessionDescription
                    .getMediaDescriptions(true).get(0);
            ((MediaDescriptionImpl) mediaDescription).setDuplexity("recvonly");
            ContentTypeHeader cth = ProtocolObjects.headerFactory.createContentTypeHeader(
                    "application", "sdp");

            newRequest.setContent(sessionDescription.toString(), cth);

            TransactionApplicationData tad = TransactionApplicationData.attach(ct,
                    Operation.SEND_INVITE_TO_MOH_SERVER);

            tad.clientTransactionProvider = Gateway.getLanProvider();
            tad.incomingSession = null;
            tad.outgoingSession = null;

            tad.serverTransactionProvider = null;
            tad.backToBackUa = this;
            this.addDialog(ct.getDialog());
            DialogApplicationData dat = DialogApplicationData.attach(this, ct.getDialog(), ct, ct
                    .getRequest());

            this.musicOnHoldDialog = ct.getDialog();

            retval = ct;

        } catch (InvalidArgumentException ex) {
            logger.error("Unexpected exception encountered");
            throw new RuntimeException("Unexpected exception encountered", ex);
        } catch (Exception ex) {
            logger.error("Unexpected parse exception", ex);
            if (retval != null)
                this.dialogTable.remove(retval);

        }
        return retval;
    }

    /**
     * Send a BYE to the Music On Hold Server.
     * 
     * @param musicOnHoldDialog
     * @throws SipException
     */
    void sendByeToMohServer() {
        try {
            if (this.musicOnHoldDialog != null
                    && this.musicOnHoldDialog.getState() != DialogState.TERMINATED) {
                logger.debug("sendByeToMohServer");
                Request byeRequest = musicOnHoldDialog.createRequest(Request.BYE);
                SipProvider lanProvider = Gateway.getLanProvider();
                ClientTransaction ctx = lanProvider.getNewClientTransaction(byeRequest);
                TransactionApplicationData.attach(ctx, Operation.SEND_BYE_TO_MOH_SERVER);
                musicOnHoldDialog.sendRequest(ctx);
            }
        } catch (SipException ex) {
            logger.error("Unexpected exception ", ex);
            throw new RuntimeException(ex);
        }

    }

    /**
     * Send an INVITE with no SDP to the peer dialog.
     * 
     * @param requestEvent
     * @param continuation
     * @param continuationData
     * @throws Exception
     */
    boolean querySdpFromPeerDialog(RequestEvent requestEvent, Operation continuation,
            ContinuationData continuationData) throws Exception {
        try {
            Dialog dialog = requestEvent.getDialog();
            Dialog peerDialog = DialogApplicationData.getPeerDialog(dialog);
            DialogApplicationData peerDat = DialogApplicationData.get(peerDialog);
            if (peerDat.isSdpAnswerPending) {
                return false;
            }

            peerDat.isSdpAnswerPending = true;

            if (peerDialog != null && peerDialog.getState() != DialogState.TERMINATED) {
                logger.debug("queryDialogFromPeer -- sending query to " + peerDialog);
                Request reInvite = peerDialog.createRequest(Request.INVITE);
                reInvite.removeHeader(SupportedHeader.NAME);
                SipUtilities.addWanAllowHeaders(reInvite);
                SipProvider provider = ((DialogExt) peerDialog).getSipProvider();
                ViaHeader viaHeader = SipUtilities.createViaHeader(provider, itspAccountInfo);
                reInvite.setHeader(viaHeader);
                ContactHeader contactHeader = SipUtilities.createContactHeader(provider,
                        itspAccountInfo);
                ContentTypeHeader cth = ProtocolObjects.headerFactory.createContentTypeHeader(
                        "application", "sdp");
                reInvite.setHeader(cth);
                reInvite.setHeader(contactHeader);
                AcceptHeader acceptHeader = ProtocolObjects.headerFactory.createAcceptHeader(
                        "application", "sdp");
                reInvite.setHeader(acceptHeader);
                ClientTransaction ctx = provider.getNewClientTransaction(reInvite);
                TransactionApplicationData tad = TransactionApplicationData.attach(ctx,
                        Operation.QUERY_SDP_FROM_PEER_DIALOG);
                tad.continuationOperation = continuation;
                tad.continuationData = continuationData;

                peerDialog.sendRequest(ctx);

            }
            return true;
        } catch (Exception ex) {
            logger.error("Exception occured. tearing down call! ", ex);
            this.tearDown();
            return true;
        }

    }

    /**
     * Set up an outgoing invite to a given ITSP.
     * 
     * @throws SipException
     * @throws Exception
     */
    void sendInviteToItsp(RequestEvent requestEvent, ServerTransaction serverTransaction,
            String toDomain, boolean isphone) throws GatewayConfigurationException, SipException {

        logger.debug("sendInviteToItsp: " + this);
        Request incomingRequest = serverTransaction.getRequest();
        Dialog incomingDialog = serverTransaction.getDialog();
        SipProvider itspProvider = Gateway
                .getWanProvider(itspAccountInfo == null ? Gateway.DEFAULT_ITSP_TRANSPORT
                        : itspAccountInfo.getOutboundTransport());

        if (Gateway.getCallLimit() != -1 && Gateway.getCallCount() >= Gateway.getCallLimit()) {
            try {
                serverTransaction.sendResponse(ProtocolObjects.messageFactory.createResponse(
                        Response.BUSY_HERE, incomingRequest));
            } catch (Exception e) {
                String s = "Unepxected exception ";
                logger.fatal(s, e);
                throw new RuntimeException(s, e);
            }
        }

        boolean spiral = false;

        ListIterator headerIterator = incomingRequest.getHeaders(ViaHeader.NAME);
        while (headerIterator.hasNext()) {
            ViaHeader via = (ViaHeader) headerIterator.next();
            String originator = via.getParameter(ORIGINATOR);
            if (originator != null && originator.equals(SIPXBRIDGE)) {
                spiral = true;
            }
        }

        ReplacesHeader replacesHeader = (ReplacesHeader) incomingRequest.getHeader(ReplacesHeader.NAME);
       
        if (logger.isDebugEnabled()) {
            logger.debug("sendInviteToItsp: spiral=" + spiral);
        }
        try {
            if (replacesHeader != null) {
               
                /* Fetch the Dialog object corresponding to the ReplacesHeader */
                Dialog replacedDialog = ((SipStackExt) ProtocolObjects.sipStack)
                        .getReplacesDialog(replacesHeader);

                if (replacedDialog == null) {

                    Response response = ProtocolObjects.messageFactory.createResponse(
                            Response.NOT_FOUND, incomingRequest);
                    response.setReasonPhrase("Replaced Dialog not found");
                    serverTransaction.sendResponse(response);
                    return;
                }

                if (spiral) {
                    handleSpriralInviteWithReplaces(requestEvent, replacedDialog,
                            serverTransaction, toDomain, isphone);
                } else {
                    handleInviteWithReplaces(requestEvent, replacedDialog, serverTransaction);
                }

                return;
            }

            SipURI incomingRequestUri = (SipURI) incomingRequest.getRequestURI();

            FromHeader fromHeader = (FromHeader) incomingRequest.getHeader(FromHeader.NAME)
                    .clone();

            Request outgoingRequest = SipUtilities.createInviteRequest(
                    (SipURI) incomingRequestUri.clone(), itspProvider, itspAccountInfo,
                    fromHeader, this.creatingCallId + "." + this.counter++, isphone);
            SipUtilities.addWanAllowHeaders(outgoingRequest);
            ClientTransaction ct = itspProvider.getNewClientTransaction(outgoingRequest);
            Dialog outboundDialog = ct.getDialog();

            DialogApplicationData.attach(this, outboundDialog, ct, outgoingRequest);
            DialogApplicationData.get(outboundDialog).setItspInfo(itspAccountInfo);

            SessionDescription sd = spiral ? DialogApplicationData.getRtpSession(
                    this.referingDialog).getReceiver().getSessionDescription() : this
                    .getWanRtpSession(outboundDialog).getReceiver().getSessionDescription();

            String address = itspAccountInfo.isGlobalAddressingUsed() ? this.symmitronClient
                    .getPublicAddress() : this.symmitronClient.getExternalAddress();

            SipUtilities.fixupSdpAddresses(sd, address);
            String codecName = Gateway.getCodecName();

            SessionDescription newSd = SipUtilities.cleanSessionDescription(sd, codecName);
            if (newSd == null) {
                Response response = ProtocolObjects.messageFactory.createResponse(
                        Response.NOT_ACCEPTABLE_HERE, incomingRequest);
                serverTransaction.sendResponse(response);
                return;
            }

            /*
             * Indicate that we will be transmitting first.
             */

            ContentTypeHeader cth = ProtocolObjects.headerFactory.createContentTypeHeader(
                    "application", "sdp");

            outgoingRequest.setContent(sd.toString(), cth);

            ListeningPoint lp = itspProvider.getListeningPoint(this.itspAccountInfo
                    .getOutboundTransport());
            String sentBy = lp.getSentBy();
            if (this.getItspAccountInfo().isGlobalAddressingUsed()) {
                lp.setSentBy(Gateway.getGlobalAddress() + ":" + lp.getPort());
            }
            lp.setSentBy(sentBy);

            /*
             * If we spiraled back, then pair the refered dialog with the outgoing dialog.
             */
            if (!spiral) {
                pairDialogs(incomingDialog, outboundDialog);

            } else {
                pairDialogs(this.referingDialogPeer, outboundDialog);

            }

            /*
             * This prepares for an authentication challenge.
             */
            TransactionApplicationData tad = new TransactionApplicationData(serverTransaction,
                    Operation.SEND_INVITE_TO_ITSP);

            tad.serverTransactionProvider = Gateway.getLanProvider();
            tad.itspAccountInfo = itspAccountInfo;
            tad.outgoingSession = this.getWanRtpSession(outboundDialog);
            tad.backToBackUa = this;
            tad.setClientTransaction(ct);

            SessionDescription sessionDescription = SipUtilities
                    .getSessionDescription(incomingRequest);

            if (!spiral) {
                tad.incomingSession = this.getLanRtpSession(incomingDialog);

                KeepaliveMethod keepAliveMethod = itspAccountInfo.getRtpKeepaliveMethod();
                RtpTransmitterEndpoint rtpEndpoint = new RtpTransmitterEndpoint(
                        tad.incomingSession, symmitronClient);
                rtpEndpoint.setKeepAliveMethod(keepAliveMethod);
                tad.incomingSession.setTransmitter(rtpEndpoint);
                rtpEndpoint.setSessionDescription(sessionDescription);

            } else if (spiral && replacesHeader == null) {
                /*
                 * This is a spiral. We are going to reuse the port in the incoming INVITE (we
                 * already own this port). Note here that we set the early media flag.
                 */

                if (this.rtpBridge.getState() == BridgeState.RUNNING) {
                    this.rtpBridge.pause(); // Pause to block inbound packets.
                }

                tad.operation = Operation.SPIRAL_BLIND_TRANSFER_INVITE_TO_ITSP;
                RtpSession rtpSession = DialogApplicationData.getRtpSession(this.referingDialog);
                if (rtpSession == null) {
                    Response errorResponse = ProtocolObjects.messageFactory.createResponse(
                            Response.SESSION_NOT_ACCEPTABLE, incomingRequest);
                    errorResponse.setReasonPhrase("Could not RtpSession for refering dialog");
                    serverTransaction.sendResponse(errorResponse);
                    if (this.rtpBridge.getState() == BridgeState.PAUSED) {
                        this.rtpBridge.resume();
                    }

                    return;
                }
                tad.referingDialog = referingDialog;
                tad.referRequest = DialogApplicationData.get(referingDialog).referRequest;
                RtpTransmitterEndpoint rtpEndpoint = new RtpTransmitterEndpoint(rtpSession,
                        symmitronClient);
                rtpSession.setTransmitter(rtpEndpoint);
                rtpEndpoint.setSessionDescription(sessionDescription);
                int keepaliveInterval = Gateway.getMediaKeepaliveMilisec();
                KeepaliveMethod keepaliveMethod = tad.itspAccountInfo.getRtpKeepaliveMethod();
                rtpEndpoint.setIpAddressAndPort(keepaliveInterval, keepaliveMethod);

                /*
                 * The RTP session now belongs to the ClientTransaction.
                 */
                this.rtpBridge.addSym(rtpSession);

                if (this.rtpBridge.getState() == BridgeState.PAUSED) {
                    this.rtpBridge.resume(); /* Resume operation. */
                } else if (rtpBridge.getState() == BridgeState.INITIAL) {
                    this.rtpBridge.start();
                }

            } else {
                logger.fatal("Internal error -- case not covered");
                throw new RuntimeException("Case not covered");
            }

            ct.setApplicationData(tad);
            this.addDialog(ct.getDialog());

            /*
             * Record the call for this dialog.
             */
            this.addDialog(ct.getDialog());
            assert this.dialogTable.size() < 3;
            logger.debug("Dialog table size = " + this.dialogTable.size());
            ct.sendRequest();

        } catch (SdpParseException ex) {
            try {
                serverTransaction.sendResponse(ProtocolObjects.messageFactory.createResponse(
                        Response.BAD_REQUEST, incomingRequest));
            } catch (Exception e) {
                String s = "Unepxected exception ";
                logger.fatal(s, e);
                throw new RuntimeException(s, e);
            }
        } catch (ParseException ex) {
            String s = "Unepxected exception ";
            logger.fatal(s, ex);
            throw new RuntimeException(s, ex);
        } catch (IOException ex) {
            logger.error("Caught IO exception ", ex);
            for (Dialog dialog : this.dialogTable) {
                dialog.delete();
            }
        } catch (Exception ex) {
            logger.error("Error occurred during processing of request ", ex);
            try {
                Response response = ProtocolObjects.messageFactory.createResponse(
                        Response.SERVER_INTERNAL_ERROR, incomingRequest);
                response.setReasonPhrase("Unexpected Exception occured at "
                        + ex.getStackTrace()[1].getFileName() + ":"
                        + ex.getStackTrace()[1].getLineNumber());
                serverTransaction.sendResponse(response);
            } catch (Exception e) {
                logger.error("Unexpected exception ", e);
            }
        }

    }

    /**
     * Handle an incoming INVITE with a replaces header.
     * 
     * @param requestEvent
     * @param replacedDialog
     * @param serverTransaction
     * @throws Exception
     */
    void handleInviteWithReplaces(RequestEvent requestEvent, Dialog replacedDialog,
            ServerTransaction serverTransaction) throws Exception {
        Request request = requestEvent.getRequest();
        SessionDescription sd = SipUtilities.getSessionDescription(request);
        logger.debug("handleInviteWithReplaces: replacedDialog = " + replacedDialog);
        String address = ((ViaHeader) request.getHeader(ViaHeader.NAME)).getHost();
        DialogApplicationData inviteDat = DialogApplicationData
                .get(serverTransaction.getDialog());
        Dialog dialog = serverTransaction.getDialog();

        try {
            RtpSession rtpSession = this.getLanRtpSession(replacedDialog);
            String ipAddress = SipUtilities.getSessionDescriptionMediaIpAddress(sd);
            int port = SipUtilities.getSessionDescriptionMediaPort(sd);
            if (rtpSession.getTransmitter() != null) {
                // Already has a transmitter then simply redirect the transmitter
                // to the new location.
                rtpSession.getTransmitter().setIpAddressAndPort(ipAddress, port);
            } else {
                RtpTransmitterEndpoint transmitter = new RtpTransmitterEndpoint(rtpSession,
                        Gateway.getSymmitronClient(address));
                transmitter.setIpAddressAndPort(ipAddress, port);
                rtpSession.setTransmitter(transmitter);
            }

            if ( this.musicOnHoldDialog != null ) {
            	this.sendByeToMohServer();
            	rtpSession.getTransmitter().setOnHold(false);
            }
            
            if (Gateway.isReInviteSupported()
                    && replacedDialog.getState() == DialogState.CONFIRMED) {
                DialogApplicationData replacedDialogApplicationData = DialogApplicationData
                        .get(replacedDialog);
               
                Dialog peerDialog = replacedDialogApplicationData.peerDialog;
                DialogApplicationData peerDat = DialogApplicationData.get(peerDialog);

                Request reInvite = peerDialog.createRequest(Request.INVITE);
                SipUtilities.addWanAllowHeaders(reInvite);

                RtpSession wanRtpSession = peerDat.getRtpSession();
                wanRtpSession.getReceiver().setSessionDescription(sd);
                SipUtilities.incrementSessionVersion(sd);
                ContentTypeHeader contentTypeHeader = ProtocolObjects.headerFactory
                        .createContentTypeHeader("application", "sdp");
                reInvite.setContent(sd.toString(), contentTypeHeader);

                SipProvider provider = ((DialogExt) peerDialog).getSipProvider();
                ClientTransaction ctx = provider.getNewClientTransaction(reInvite);
                TransactionApplicationData tad = TransactionApplicationData.attach(ctx,
                        Operation.HANDLE_INVITE_WITH_REPLACES);
                tad.setServerTransaction(serverTransaction);
                tad.replacedDialog = replacedDialog;

                // send the in-dialog re-invite to the other side.
                peerDialog.sendRequest(ctx);
            } else {
                /*
                 * Re-INVITE is not supported. Remap the transmitter side and hang up the replaced
                 * dialog.
                 */

                SessionDescription sdes = rtpSession.getReceiver().getSessionDescription();

                String selectedCodec = null;

                if (replacedDialog.getState() != DialogState.CONFIRMED) {
                    HashSet<Integer> codecs = null;
                    codecs = SipUtilities.getCommonCodec(sdes, sd);

                    if (codecs.size() == 0) {
                        Response errorResponse = ProtocolObjects.messageFactory.createResponse(
                                Response.NOT_ACCEPTABLE_HERE, request);
                        serverTransaction.sendResponse(errorResponse);
                        return;

                    }
                    selectedCodec = RtpPayloadTypes.getPayloadType(codecs.iterator().next());
                    SipUtilities.cleanSessionDescription(sdes, selectedCodec);
                }
                
                /*
                 * Track the RTP session we were using.
                 */
                inviteDat.rtpSession = rtpSession;
                
                /*
                 * Generate an OK response to be sent after BYE OK comes in from transfer
                 * agent.
                 */
                Response okResponse = ProtocolObjects.messageFactory.createResponse(Response.OK,
                        request);

                ContentTypeHeader cth = ProtocolObjects.headerFactory.createContentTypeHeader(
                        "application", "sdp");
                SipProvider txProvider = ((TransactionExt) serverTransaction).getSipProvider();
                ContactHeader contactHeader = SipUtilities.createContactHeader(
                        Gateway.SIPXBRIDGE_USER, txProvider);
                okResponse.setHeader(contactHeader);
                okResponse.setContent(sdes.toString(), cth);
                DialogApplicationData replacedDat = DialogApplicationData.get(replacedDialog);
                if (replacedDat.transaction != null
                        && replacedDat.transaction instanceof ClientTransaction) {
                    Request cancelRequest = ((ClientTransaction) replacedDat.transaction)
                            .createCancel();
                    if (replacedDat.transaction.getState() != TransactionState.TERMINATED) {
                        ClientTransaction cancelTx = txProvider
                                .getNewClientTransaction(cancelRequest);
                        cancelTx.sendRequest();
                    }
                }

                Request byeRequest = replacedDialog.createRequest(Request.BYE);
                SipProvider provider = ((DialogExt) replacedDialog).getSipProvider();
                ClientTransaction byeCtx = provider.getNewClientTransaction(byeRequest);
                
                /*
                 * Create a little transaction context to identify the operator in our state machine.
                 * when we see a response to the BYE.
                 */
                TransactionApplicationData.attach(byeCtx, Operation.SEND_BYE_TO_REPLACED_DIALOG);
                

                /*
                 * bid adeu to the replaced dialog. 
                 */
                replacedDialog.sendRequest(byeCtx);
                
                serverTransaction.sendResponse(okResponse);

                /*
                 * The following condition happens during call pickup.
                 */
                if (replacedDialog.getState() != DialogState.CONFIRMED) {
                    /*
                     * The other side has not been sent an SDP answer yet. Extract the OFFER from
                     * the inbound INVITE and send it as an answer.
                     */
                    DialogApplicationData replacedDialogApplicationData = DialogApplicationData
                            .get(dialog);

                    Dialog peerDialog = replacedDialogApplicationData.peerDialog;
                    DialogApplicationData peerDat = DialogApplicationData.get(peerDialog);
                    RtpSession wanRtpSession = peerDat.getRtpSession();
                    SipProvider wanProvider = ((DialogExt) peerDialog).getSipProvider();
                    ContactHeader contact = SipUtilities.createContactHeader(wanProvider, peerDat
                            .getItspInfo());
                    wanRtpSession.getReceiver().setSessionDescription(sd);

                    SipUtilities.cleanSessionDescription(sd, selectedCodec);

                    ServerTransaction peerSt = ((ServerTransaction) peerDat.transaction);
                    Response peerOk = ProtocolObjects.messageFactory.createResponse(Response.OK,
                            peerSt.getRequest());
                    peerOk.setHeader(contact);
                    peerOk.setContent(sd.toString(), cth);
                    peerSt.sendResponse(peerOk);
                    this.getRtpBridge().start();

                }

            }
        } catch (Exception ex) {
            logger.error("Unexpected exception -- tearing down call ", ex);
            try {
                this.tearDown(ProtocolObjects.headerFactory.createWarningHeader("sipxbridge",
                        103, ex.getMessage()));
            } catch (Exception e) {
                logger.error("Unexpected exception ", e);
            }
        }

    }

    /**
     * Handle a bye on one of the dialogs of this b2bua.
     * 
     * @param dialog
     * @throws SipException
     */
    void processBye(RequestEvent requestEvent) throws SipException {
        Dialog dialog = requestEvent.getDialog();
        ServerTransaction st = requestEvent.getServerTransaction();

        DialogApplicationData dad = (DialogApplicationData) dialog.getApplicationData();
        Dialog peer = dad.peerDialog;

        if ((!dad.isReferAccepted) && peer != null && peer.getState() != DialogState.TERMINATED
                && peer.getState() != null) {
            SipProvider provider = ((gov.nist.javax.sip.DialogExt) peer).getSipProvider();

            Request bye = peer.createRequest(Request.BYE);

            ClientTransaction ct = provider.getNewClientTransaction(bye);

            TransactionApplicationData tad = TransactionApplicationData.attach(st,
                    Operation.PROCESS_BYE);

            tad.setClientTransaction(ct);
            tad.itspAccountInfo = this.itspAccountInfo;
            ct.setApplicationData(tad);

            peer.sendRequest(ct);
        } else {
            /*
             * Peer dialog is not yet established or is terminated.
             */
            logger.debug("BackToBackUserAgent: peerDialog = " + peer);
            if (peer != null) {
                logger.debug("BackToBackUserAgent: peerDialog state = " + peer.getState());
            }
            try {
                Response ok = ProtocolObjects.messageFactory.createResponse(Response.OK, st
                        .getRequest());
                SupportedHeader sh = ProtocolObjects.headerFactory
                        .createSupportedHeader("replaces");
                ok.setHeader(sh);
                st.sendResponse(ok);

            } catch (InvalidArgumentException ex) {
                logger.error("Unexpected exception", ex);
            } catch (ParseException ex) {
                logger.error("Unexpecte exception", ex);

            }
        }

    }

    /**
     * Return true if theres stuff in the dialog table and false otherwise.
     * 
     * @return
     */
    boolean isEmpty() {
        return this.dialogTable.isEmpty();
    }

    /**
     * @return the rtpBridge
     */
    RtpBridge getRtpBridge() {
        return rtpBridge;
    }

    /**
     * @return the creatingDialog
     */
    Dialog getCreatingDialog() {
        return creatingDialog;
    }

    /**
     * Set the ITSP account info.
     * 
     * @param account
     */
    void setItspAccount(ItspAccountInfo account) {
        this.itspAccountInfo = account;

    }

    SymmitronClient getSymmitronClient() {
        return symmitronClient;
    }

    // ///////////////////////////////////////////////////////////////////////
    // Public methods - invoked by xml rpc server.
    // //////////////////////////////////////////////////////////////////////

    /**
     * Terminate the two sides of the bridge. This method is invoked by xml rpc interface and is
     * hence public.
     */
    public void tearDown() {

        try {
            for (Dialog dialog : this.dialogTable) {
                if (dialog.getState() != DialogState.TERMINATED) {
                    Request byeRequest = dialog.createRequest(Request.BYE);
                    SipProvider provider = ((DialogExt) dialog).getSipProvider();
                    ClientTransaction ct = provider.getNewClientTransaction(byeRequest);
                    dialog.sendRequest(ct);
                }
            }
        } catch (Exception ex) {
            logger.error("Unexpected exception ", ex);
        }
    }

    /**
     * @return the symmitronServerHandle
     */
    String getSymmitronServerHandle() {
        return symmitronServerHandle;
    }

    public void tearDown(WarningHeader warning) throws Exception {
        for (Dialog dialog : this.dialogTable) {
            if (dialog.getState() != DialogState.TERMINATED) {
                Request byeRequest = dialog.createRequest(Request.BYE);
                byeRequest.addHeader(warning);
                SipProvider provider = ((DialogExt) dialog).getSipProvider();
                ClientTransaction ct = provider.getNewClientTransaction(byeRequest);
                dialog.sendRequest(ct);
            }
        }

    }

    /**
     * @return the musicOnHoldDialog
     */
    public Dialog getMusicOnHoldDialog() {
        return musicOnHoldDialog;
    }

	public SessionDescription getTransmitterSd(Dialog dialog) throws IOException {
		SessionDescription transmitterSd = ((DialogExt) dialog)
		.getSipProvider() == Gateway.getLanProvider() ? this
		.getLanRtpSession(dialog).getTransmitter()
		.getSessionDescription() : this.getWanRtpSession(
		dialog).getTransmitter().getSessionDescription();
		return transmitterSd;
	}

}
