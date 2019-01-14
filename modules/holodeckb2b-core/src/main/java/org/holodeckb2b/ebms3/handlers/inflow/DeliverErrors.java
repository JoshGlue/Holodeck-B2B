/**
 * Copyright (C) 2014 The Holodeck B2B Team, Sander Fieten
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.holodeckb2b.ebms3.handlers.inflow;

import java.util.Collection;
import java.util.Map;

import org.apache.axis2.context.MessageContext;
import org.holodeckb2b.common.handler.BaseHandler;
import org.holodeckb2b.common.messagemodel.ErrorMessage;
import org.holodeckb2b.common.util.Utils;
import org.holodeckb2b.ebms3.constants.MessageContextProperties;
import org.holodeckb2b.interfaces.core.HolodeckB2BCoreInterface;
import org.holodeckb2b.interfaces.delivery.IDeliverySpecification;
import org.holodeckb2b.interfaces.delivery.IMessageDeliverer;
import org.holodeckb2b.interfaces.delivery.MessageDeliveryException;
import org.holodeckb2b.interfaces.messagemodel.IErrorMessage;
import org.holodeckb2b.interfaces.messagemodel.IPullRequest;
import org.holodeckb2b.interfaces.persistency.PersistenceException;
import org.holodeckb2b.interfaces.persistency.entities.IErrorMessageEntity;
import org.holodeckb2b.interfaces.persistency.entities.IMessageUnitEntity;
import org.holodeckb2b.interfaces.pmode.IErrorHandling;
import org.holodeckb2b.interfaces.pmode.ILeg;
import org.holodeckb2b.interfaces.pmode.IPMode;
import org.holodeckb2b.interfaces.pmode.IPullRequestFlow;
import org.holodeckb2b.interfaces.pmode.IUserMessageFlow;
import org.holodeckb2b.interfaces.processingmodel.ProcessingState;
import org.holodeckb2b.module.HolodeckB2BCore;
import org.holodeckb2b.persistency.dao.StorageManager;
import org.holodeckb2b.pmode.PModeUtils;

/**
 * Is the <i>IN_FLOW</i> handler responsible for checking if error message should be delivered to the business
 * application and if so to hand them over to the responsible {@link IMessageDeliverer}.
 * <p>To prevent that errors in the error message unit are delivered twice in parallel delivery only takes place when
 * the processing state of the unit can be successfully changed from {@link ProcessingState#READY_FOR_DELIVERY} to
 * {@link ProcessingState#OUT_FOR_DELIVERY}.
 * <p>To enable easy monitoring all received error signals will always be logged to a separate log
 * (<code>org.holodeckb2b.msgproc.errors.received</code>).
 * <p>NOTE: The actual delivery to the business application is done through a {@link IMessageDeliverer} which is
 * specified in the P-Mode for this message unit. That P-Mode is the same as the P-Mode of the referenced message or
 * if no message is referenced and this message unit is received as a response the primary message unit in the request.
 *
 * @author Sander Fieten (sander at holodeck-b2b.org)
 */
public class DeliverErrors extends BaseHandler {

    @Override
    protected byte inFlows() {
        return IN_FLOW | IN_FAULT_FLOW;
    }

    @SuppressWarnings("unchecked")
	@Override
    protected InvocationResponse doProcessing(final MessageContext mc) throws PersistenceException {
        // Check if this message contains error signals
        final Collection<IErrorMessageEntity> errorSignals = (Collection<IErrorMessageEntity>)
                                                                    mc.getProperty(MessageContextProperties.IN_ERRORS);

        if (Utils.isNullOrEmpty(errorSignals))
            // No errors to deliver
            return InvocationResponse.CONTINUE;

        StorageManager updateManager = HolodeckB2BCore.getStorageManager();
        // Get the mapping of Error siganls to referenced message units from the message context
        final Map<IErrorMessage, Collection<IMessageUnitEntity>> errorToMsgMap = 
												(Map<IErrorMessage, Collection<IMessageUnitEntity>>) 
												mc.getProperty(MessageContextProperties.MAP_REFD_MSGS_BY_ERRORS);
        // Process each signal        
        for(final IErrorMessageEntity errorSignal : errorSignals) {
            // Prepare message for delivery by checking it is still ready for delivery and then
            // change its processing state to "out for delivery"
            log.trace("Prepare Error Signal [" + errorSignal.getMessageId() + "] for delivery");

            if(updateManager.setProcessingState(errorSignal, ProcessingState.READY_FOR_DELIVERY,
                                                             ProcessingState.OUT_FOR_DELIVERY)) {
                // Errors in this signal can be delivered to business application
                log.debug("Start delivery of Error Signal [" + errorSignal.getMessageId() + "]");
            	// Get the message units referenced by this error
            	Collection<IMessageUnitEntity> refdMessages = errorToMsgMap.get(errorSignal);
            	boolean deliveredForAll = true;
            	for(final IMessageUnitEntity msgInError : refdMessages)
                	deliveredForAll &= deliverError(errorSignal, msgInError);
            	log.debug("Reported Error Signal for all referenced message units");
            	if (deliveredForAll) 
	            	updateManager.setProcessingState(errorSignal, ProcessingState.DONE);
            	else 
            		updateManager.setProcessingState(errorSignal, ProcessingState.WARNING, 
            										"Error could not be delivered for [all] referenced message units");
            } else
                log.info("Error signal [" + errorSignal.getMessageId() + "] is already processed for delivery");            
        }
        log.debug("Processed all Error signals in message");
        return InvocationResponse.CONTINUE;
    }

    /**
     * Is a helper method responsible for delivering an Error Signal to the business application. Whether the Error 
     * needs to be delivered is defined by the P-Mode of the referenced message unit in the {@link 
     * IDeliverySpecification} for errors. 
     *
     * @param errorSignal   The Error signal that must be delivered
     * @param msgInError	The message unit referenced by the Error and which P-Mode defines if and how to report the
     * 						error
     * @return <code>false</code> if the delivery of the error to back-end failed,<br>
     * 		   <code>true</code> otherwise 
     * @throws PersistenceException    When an error occurs retrieving the message unit referenced by the Error Signal
     */
    private boolean deliverError(final IErrorMessageEntity errorSignal, final IMessageUnitEntity msgInError)
            																			throws PersistenceException {
        
        log.trace("Get delivery specification for error from P-Mode of refd message [msgId=" 
        			+ msgInError.getMessageId() + "]");
        IDeliverySpecification deliverySpec = getErrorDelivery(msgInError);        
        // If a delivery specification was found the error should be delivered, else no reporting is needed
        if (deliverySpec != null) {
        	IMessageDeliverer deliverer = null;
            try {
                log.debug("Error Signal should be delivered using delivery specification with id:" 
                			+ deliverySpec.getId());
                deliverer = HolodeckB2BCoreInterface.getMessageDeliverer(deliverySpec);
                log.trace("Delivering the error using deliverer");
                // Because the reference to the message in error may be derived, set it explicitly on signal meta-data
                // See also issue #12
                ErrorMessage deliverySignal = new ErrorMessage(errorSignal);
                deliverySignal.setRefToMessageId(msgInError.getMessageId());
                deliverer.deliver(deliverySignal);
                log.info("Error Signal [msgId= " + errorSignal.getMessageId() 
                		+ "] successfully delivered for referenced message unit [msgId=" + msgInError.getMessageId() 
                		+ "]!");
            } catch (final Throwable t) {
            	if (!(t instanceof MessageDeliveryException))
                    log.error(deliverer.getClass().getSimpleName() + " threw " + t.getClass().getSimpleName()
                            	+ " instead of MessageDeliveryException!");
            	
                log.warn("Could not deliver Error Signal (msgId=" + errorSignal.getMessageId()
	                		+ "]) for referenced message [msgId=" + msgInError.getMessageId() 
	                		+ "] to application! Error details: " + t.getMessage());
                return false;
            }
        } else
            log.debug("Error does not need to (or can not) be delivered");

        return true;
    }

    /**
     * Is a helper method to determine if and how an Error Signal should be delivered to the business application. The
     * P-Mode of the referenced message unit (which is also the P-Mode for the error) is used for this check. It depends
     * on the type of the referenced message unit which P-Mode setting determines whether the error should be delivered
     * and how:<ul>
     * <li>For User message and Receipt signal : <code>PMode.Leg.usermessage.ErrorHandling</code></li>
     * <li>Pull Request : First <code>PMode.leg.pullrequest.ErrorHandling</code> and if that is not specified <code>
     * PMode.Leg.usermessage.ErrorHandling</code></li>
     * <li>Error signal : Here also <code>PMode.Leg.usermessage.ErrorHandling</code> is used. But this can only be done
     * if the sent error was assigned to a P-Mode. This is not guaranteed, so it possible that no error handling
     * configuration can be retrieved. In which case the error will not be delivered.
     * </ul>
     * How the error is delivered is defined by the delivery specification linked to the error handling configuration.
     * If that is not set the default delivery specification [of the Leg] will be used.
     *
     * @param refdMU    The message unit referenced by the error
     * @return          When the error should be delivered to the business application, the {@link
     *                  IDeliverySpecification} that should be used for the delivery,<br>
     *                  <code>null</code> otherwise
     */
    private IDeliverySpecification getErrorDelivery(final IMessageUnitEntity refdMU) {
        IDeliverySpecification deliverySpec = null;

        if (Utils.isNullOrEmpty(refdMU.getPModeId()))
            return null; // Referenced message unit without P-Mode, can not determine delivery

        final IPMode pmode = HolodeckB2BCoreInterface.getPModeSet().get(refdMU.getPModeId());
        if (pmode == null) {
            log.warn("Sent message unit [" + refdMU.getMessageId() +"] does not reference valid P-Mode ["
                        + refdMU.getPModeId() + "]!");
            return null;
        }
        // First get the delivery specification for errors related to the user message as this will also be the fall
        // back for errors related to pull request if nothing is specified specifically for pull requests
        final ILeg leg = pmode.getLeg(refdMU.getLeg());
        final IUserMessageFlow umFlow = leg.getUserMessageFlow();
        IErrorHandling errHandling = umFlow != null ? umFlow.getErrorHandlingConfiguration() : null;

        if (refdMU instanceof IPullRequest) {
            // Check if the pull request have their own error handling
            final IPullRequestFlow prFlow = PModeUtils.getOutPullRequestFlow(pmode);
            errHandling = prFlow != null && prFlow.getErrorHandlingConfiguration() != null ?
                                                                   prFlow.getErrorHandlingConfiguration() : errHandling;
        }

        if (errHandling != null)
            deliverySpec = errHandling.getErrorDelivery();
        if (deliverySpec == null)
            deliverySpec = leg.getDefaultDelivery();

        if (errHandling != null && errHandling.shouldNotifyErrorToBusinessApplication())
            return deliverySpec;
        else
            return null;
    }
}
