/*
 * Copyright 2017 Wultra s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.getlime.security.powerauth.lib.webflow.authentication.controller;

import io.getlime.core.rest.model.base.response.ObjectResponse;
import io.getlime.security.powerauth.lib.dataadapter.client.DataAdapterClient;
import io.getlime.security.powerauth.lib.dataadapter.client.DataAdapterClientErrorException;
import io.getlime.security.powerauth.lib.dataadapter.model.entity.FormData;
import io.getlime.security.powerauth.lib.dataadapter.model.entity.OperationChange;
import io.getlime.security.powerauth.lib.dataadapter.model.entity.OperationContext;
import io.getlime.security.powerauth.lib.dataadapter.model.enumeration.OperationTerminationReason;
import io.getlime.security.powerauth.lib.nextstep.client.NextStepClient;
import io.getlime.security.powerauth.lib.nextstep.model.entity.*;
import io.getlime.security.powerauth.lib.nextstep.model.enumeration.*;
import io.getlime.security.powerauth.lib.nextstep.model.exception.*;
import io.getlime.security.powerauth.lib.nextstep.model.response.*;
import io.getlime.security.powerauth.lib.webflow.authentication.base.AuthStepRequest;
import io.getlime.security.powerauth.lib.webflow.authentication.base.AuthStepResponse;
import io.getlime.security.powerauth.lib.webflow.authentication.exception.*;
import io.getlime.security.powerauth.lib.webflow.authentication.model.AuthenticationResult;
import io.getlime.security.powerauth.lib.webflow.authentication.model.converter.FormDataConverter;
import io.getlime.security.powerauth.lib.webflow.authentication.repository.model.entity.OperationSessionEntity;
import io.getlime.security.powerauth.lib.webflow.authentication.security.UserOperationAuthentication;
import io.getlime.security.powerauth.lib.webflow.authentication.service.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Base controller for any authentication method. Controller class is templated using three attributes.
 *
 * <ul>
 * <li>T - extension of AuthStepRequest.</li>
 * <li>R - extension of AuthStepResponse.</li>
 * <li>E - extension of AuthStepException.</li>
 * </ul>
 *
 * @author Petr Dvorak, petr@wultra.com
 */
@Component
public abstract class AuthMethodController<T extends AuthStepRequest, R extends AuthStepResponse, E extends AuthStepException> {

    private static final Logger logger = LoggerFactory.getLogger(AuthMethodController.class);

    @Autowired
    private AuthenticationManagementService authenticationManagementService;

    @Autowired
    private NextStepClient nextStepClient;

    @Autowired
    private AuthMethodQueryService authMethodQueryService;

    @Autowired
    private DataAdapterClient dataAdapterClient;

    @Autowired
    private MessageTranslationService messageTranslationService;

    @Autowired
    private OperationSessionService operationSessionService;

    @Autowired
    private HttpServletRequest request;

    @Autowired
    private AuthMethodResolutionService authMethodResolutionService;

    @Autowired
    private AfsIntegrationService afsIntegrationService;

    @Autowired
    private OperationCancellationService operationCancellationService;

    /**
     * Get operation detail.
     * @return Operation detail.
     * @throws AuthStepException Thrown when operation could not be retrieved or it is not available.
     */
    protected GetOperationDetailResponse getOperation() throws AuthStepException {
        return getOperation(true);
    }

    /**
     * Get operation detail.
     * @param validateOperationState Whether operation state should be validated.
     * @return Operation detail.
     * @throws AuthStepException Thrown when operation could not be retrieved or it is not available.
     */
    protected GetOperationDetailResponse getOperation(boolean validateOperationState) throws AuthStepException {
        final UserOperationAuthentication pendingUserAuthentication = authenticationManagementService.getPendingUserAuthentication();
        if (pendingUserAuthentication != null) {
            String operationId = pendingUserAuthentication.getOperationId();
            if (operationId != null) {
                return getOperation(operationId, validateOperationState);
            } else {
                throw new OperationNotAvailableException("Operation is not available");
            }
        } else {
            throw new OperationNotAvailableException("Operation is not available");
        }
    }

    /**
     * Get operation detail with given operation ID.
     * @param operationId Operation ID.
     * @return Operation detail.
     * @throws AuthStepException Thrown when operation could not be retrieved or it is not available.
     */
    protected GetOperationDetailResponse getOperation(String operationId) throws AuthStepException {
        return getOperation(operationId, true);
    }

    /**
     * Get operation detail with given operation ID.
     * @param operationId Operation ID.
     * @param validateOperationState Whether operation state should be validated.
     * @return Operation detail.
     * @throws AuthStepException Thrown when operation could not be retrieved or it is not available.
     */
    protected GetOperationDetailResponse getOperation(String operationId, boolean validateOperationState) throws AuthStepException {
        try {
            final ObjectResponse<GetOperationDetailResponse> operationDetail = nextStepClient.getOperationDetail(operationId);
            final GetOperationDetailResponse operation = operationDetail.getResponseObject();
            if (validateOperationState) {
                validateOperationState(operation);
            }
            filterStepsBasedOnActiveAuthMethods(operation.getSteps(), operation.getUserId(), operationId);
            // Convert operation definition for LOGIN_SCA step which requires login operation definition and not approval operation definition.
            // This is a temporary workaround until Web Flow supports configuration of multiple operations in a compound operation.
            updateOperationForScaLogin(operation);
            messageTranslationService.translateFormData(operation.getFormData());
            return operation;
        } catch (NextStepServiceException e) {
            logger.error("Error occurred in Next Step server", e);
            throw new CommunicationFailedException("Operation is not available");
        }
    }

    /**
     * Check whether operation is expired. In case it is expired, thrown an OperationTimeoutException.
     * @param operation Operation.
     * @throws OperationTimeoutException Thrown when operation is expired.
     */
    private void checkOperationExpiration(GetOperationDetailResponse operation) throws OperationTimeoutException {
        if (operation == null) {
            throw new IllegalArgumentException("Operation is null in checkOperationExpiration");
        }
        if (operation.isExpired()) {
            logger.info("Operation has timed out, operation ID: {}", operation.getOperationId());
            try {
                cancelAuthorization(operation.getOperationId(), operation.getUserId(), OperationCancelReason.TIMED_OUT_OPERATION, null);
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
            }
            throw new OperationTimeoutException("Operation has timed out");
        }
    }

    /**
     * Get operation configuration.
     * @param operationName Operation name.
     * @return Operation configuration.
     * @throws AuthStepException Thrown in case communication with Next Step fails.
     */
    protected GetOperationConfigDetailResponse getOperationConfig(String operationName) throws AuthStepException {
        try {
            final ObjectResponse<GetOperationConfigDetailResponse> operationConfigResponse = nextStepClient.getOperationConfigDetail(operationName);
            return operationConfigResponse.getResponseObject();
        } catch (NextStepServiceException e) {
            logger.error("Error occurred in Next Step server", e);
            throw new CommunicationFailedException("Operation configuration is not available");
        }
    }

    /**
     * Get operation configurations.
     * @return Operation configurations.
     * @throws AuthStepException Thrown in case communication with Next Step fails.
     */
    protected GetOperationConfigListResponse getOperationConfigs() throws AuthStepException {
        try {
            final ObjectResponse<GetOperationConfigListResponse> operationConfigsResponse = nextStepClient.getOperationConfigList();
            return operationConfigsResponse.getResponseObject();
        } catch (NextStepServiceException e) {
            logger.error("Error occurred in Next Step server", e);
            throw new CommunicationFailedException("Operation configuration is not available");
        }
    }

    /**
     * Get current authentication method.
     * @return Current authentication method.
     */
    protected abstract AuthMethod getAuthMethodName();

    /**
     * Get current authentication method given current operation context.
     * @param operation Current operation.
     * @return Current authentication method given current operation context.
     */
    protected AuthMethod getAuthMethodName(GetOperationDetailResponse operation) {
        // Check for authentication method override for SCA methods.
        AuthMethod overriddenAuthMethod = authMethodResolutionService.resolveAuthMethodOverride(operation);
        if (overriddenAuthMethod != null) {
            return overriddenAuthMethod;
        }
        // Regular authentication method resolution.
        return getAuthMethodName();
    }

    /**
     * Get pending operations for given user.
     * @param userId User ID.
     * @param mobileTokenOnly Whether pending operation list should be filtered for only next step with mobile token support.
     * @return List of operations for given user.
     * @throws AuthStepException Thrown in case communication with Next Step fails.
     */
    protected List<GetOperationDetailResponse> getOperationListForUser(String userId, boolean mobileTokenOnly) throws AuthStepException {
        try {
            final ObjectResponse<List<GetOperationDetailResponse>> operations = nextStepClient.getPendingOperations(userId, mobileTokenOnly);
            final List<GetOperationDetailResponse> responseObject = operations.getResponseObject();
            for (GetOperationDetailResponse operation: responseObject) {
                updateOperationForScaLogin(operation);
                // translate formData messages
                messageTranslationService.translateFormData(operation.getFormData());
            }
            return operations.getResponseObject();
        } catch (NextStepServiceException e) {
            logger.error("Error occurred in Next Step server", e);
            throw new CommunicationFailedException("Operations are not available");
        }
    }

    /**
     * Method to authenticate user with provided request object.
     *
     * @param request Request with authentication object information.
     * @return String with user ID.
     * @throws E In case error occurs during authentication.
     */
    protected AuthenticationResult authenticate(T request) throws E {
        return null;
    }

    /**
     * Authorize operation with provided ID with user with given user ID.
     *
     * @param operationId        Operation ID of operation to be authorized.
     * @param userId             User ID of user who should authorize operation.
     * @param organizationId     Organization ID of organization related to the operation.
     * @param authInstruments    Used authentication / authorization instruments.
     * @param params             Custom parameters.
     * @return Response with information about operation update result.
     * @throws NextStepServiceException In case communication fails.
     * @throws AuthStepException In case authorization fails.
     */
    protected UpdateOperationResponse authorize(String operationId, String userId, String organizationId, List<AuthInstrument> authInstruments, List<KeyValueParameter> params) throws NextStepServiceException, AuthStepException {
        // validate operation before requesting update
        GetOperationDetailResponse operation = getOperation(operationId);
        AuthMethod authMethod = getAuthMethodName(operation);
        logger.info("Step authorization started, operation ID: {}, user ID: {}, authentication method: {}", operationId, userId, authMethod.toString());
        ApplicationContext applicationContext = operation.getApplicationContext();
        ObjectResponse<UpdateOperationResponse> response = nextStepClient.updateOperation(operationId, userId, organizationId, authMethod, authInstruments, AuthStepResult.CONFIRMED, null, params, applicationContext);
        // notify Data Adapter in case operation is in DONE state now
        if (response.getResponseObject().getResult()==AuthResult.DONE) {
            try {
                FormData formData = new FormDataConverter().fromOperationFormData(operation.getFormData());
                OperationContext operationContext = new OperationContext(operation.getOperationId(), operation.getOperationName(), operation.getOperationData(), formData, applicationContext);
                dataAdapterClient.operationChangedNotification(OperationChange.DONE, userId, organizationId, operationContext);
                // notify AFS about logout
                afsIntegrationService.executeLogoutAction(operationId, OperationTerminationReason.DONE);
            } catch (DataAdapterClientErrorException ex) {
                logger.error("Error while notifying Data Adapter", ex);
            }
        }
        // update operation result in operation to HTTP session mapping
        operationSessionService.updateOperationResult(operationId, response.getResponseObject().getResult());
        filterStepsBasedOnActiveAuthMethods(response.getResponseObject().getSteps(), userId, operationId);
        logger.info("Step authorization succeeded, operation ID: {}, user ID: {}, authentication method: {}", operationId, userId, authMethod.toString());
        return response.getResponseObject();
    }

    /**
     * Fail the operation with provided operation ID with user with given user ID.
     *
     * @param operationId     Operation ID of operation to fail.
     * @param userId          User ID of user who owns the operation.
     * @param authInstruments Used authentication instruments.
     * @param params          Custom parameters.
     * @return Response with information about operation update result.
     * @throws NextStepServiceException In case communication fails.
     * @throws AuthStepException In case authorization fails.
     */
    protected UpdateOperationResponse failAuthorization(String operationId, String userId, List<AuthInstrument> authInstruments, List<KeyValueParameter> params) throws NextStepServiceException, AuthStepException {
        GetOperationDetailResponse operation = getOperation(operationId, false);
        AuthMethod authMethod = getAuthMethodName(operation);
        logger.info("Fail step started, operation ID: {}, user ID: {}, authentication method: {}", operationId, userId, authMethod.toString());
        ApplicationContext applicationContext = operation.getApplicationContext();
        ObjectResponse<UpdateOperationResponse> response = nextStepClient.updateOperation(operationId, userId, operation.getOrganizationId(), authMethod, authInstruments, AuthStepResult.AUTH_FAILED, null, params, applicationContext);
        // notify Data Adapter in case operation is in FAILED state now
        if (response.getResponseObject().getResult()==AuthResult.FAILED) {
            try {
                FormData formData = new FormDataConverter().fromOperationFormData(operation.getFormData());
                OperationContext operationContext = new OperationContext(operation.getOperationId(), operation.getOperationName(), operation.getOperationData(), formData, applicationContext);
                dataAdapterClient.operationChangedNotification(OperationChange.FAILED, userId, operation.getOrganizationId(), operationContext);
            } catch (DataAdapterClientErrorException ex) {
                logger.error("Error while notifying Data Adapter", ex);
            }
        }
        // update operation result in operation to HTTP session mapping
        operationSessionService.updateOperationResult(operationId, response.getResponseObject().getResult());
        filterStepsBasedOnActiveAuthMethods(response.getResponseObject().getSteps(), userId, operationId);
        logger.info("Fail step succeeded, operation ID: {}, user ID: {}, authentication method: {}", operationId, userId, authMethod.toString());
        return response.getResponseObject();
    }

    /**
     * @param operationId  Operation ID of operation to cancel.
     * @param userId       User ID of user who owns the operation.
     * @param params       Custom parameters.
     * @param cancelReason Reason for cancellation of the operation.
     * @return Response with information about operation update result.
     * @throws NextStepServiceException In case communication fails.
     * @throws AuthStepException In case authorization fails.
     */
    protected UpdateOperationResponse cancelAuthorization(String operationId, String userId, OperationCancelReason cancelReason, List<KeyValueParameter> params) throws NextStepServiceException, AuthStepException {
        GetOperationDetailResponse operation = getOperation(operationId, false);
        AuthMethod authMethod = getAuthMethodName(operation);
        UpdateOperationResponse updateOperationResponse = operationCancellationService.cancelOperation(operation, authMethod, cancelReason);
        if (updateOperationResponse != null) {
            filterStepsBasedOnActiveAuthMethods(updateOperationResponse.getSteps(), userId, operationId);
            return updateOperationResponse;
        }
        return null;
    }

    /**
     * Initiate a new operation with given name, data and parameters.
     *
     * @param operationName      Name of the operation to be created.
     * @param operationData      Data of the operation.
     * @param formData           Form data used for displaying the operation details.
     * @param httpSessionId      HTTP session ID.
     * @param params             Additional parameters of the operation.
     * @param applicationContext Application context.
     * @param provider           Provider that implements authentication callback.
     * @return Response indicating next step, based on provider response.
     */
    protected R initiateOperationWithName(String operationName, String operationData, OperationFormData formData, String httpSessionId, List<KeyValueParameter> params, ApplicationContext applicationContext, AuthResponseProvider provider) {
        logger.info("Operation initiate with name started, operation name: {}", operationName);
        try {
            ObjectResponse<CreateOperationResponse> response = nextStepClient.createOperation(operationName, operationData, formData, params, applicationContext);
            CreateOperationResponse responseObject = response.getResponseObject();
            String operationId = responseObject.getOperationId();
            String organizationId = responseObject.getOrganizationId();
            // Persist mapping of operation to HTTP session
            boolean registrationSucceeded = operationSessionService.registerHttpSession(operationId, httpSessionId, responseObject.getResult());
            if (!registrationSucceeded) {
                // Registration of HTTP session failed for the operation
                R initResponse = provider.failedAuthentication(null, "error.invalidRequest");
                logger.info("Operation initiate failed during registration of HTTP session, operation name: {}", operationName);
                return initResponse;
            }
            filterStepsBasedOnActiveAuthMethods(responseObject.getSteps(), null, operationId);
            authenticationManagementService.createAuthenticationWithOperationId(operationId, organizationId);
            R initResponse = provider.continueAuthentication(operationId, null, responseObject.getSteps());
            logger.info("Operation initiate succeeded, operation name: {}", operationName);
            return initResponse;
        } catch (NextStepServiceException e) {
            logger.error("Error while initiating operation", e);
            return provider.failedAuthentication(null, "error.communication");
        }
    }

    /**
     * Continue an existing operation.
     *
     * @param operationId ID of operation to be fetched.
     * @param httpSessionId HTTP session ID.
     * @param provider Provider that implements authentication callback.
     * @return Response indicating next step, based on provider response.
     */
    protected R continueOperationWithId(String operationId, String httpSessionId, AuthResponseProvider provider) {
        logger.info("Operation continue with ID started, operation ID: {}", operationId);
        try {
            final GetOperationDetailResponse operation = getOperation(operationId);
            if (operation == null) {
                // Next step call failed, next step could not be decided
                logger.info("Operation failed because operation could not be retrieved, operation ID: {}", operationId);
                return provider.failedAuthentication(null, "Operation is not available");
            }
            final String userId = operation.getUserId();
            // Check whether session is already initiated - page refresh could cause double initialization.
            // If it is not initiated yet, persist operation to session mapping.
            OperationSessionEntity operationSessionEntity = operationSessionService.getOperationToSessionMapping(operationId);
            if (operationSessionEntity == null) {
                // Cancel previous active operations
                cancelOperationsInHttpSession(httpSessionId);
                // Persist mapping of operation to HTTP session
                boolean registrationSucceeded = operationSessionService.registerHttpSession(operationId, httpSessionId, operation.getResult());
                if (!registrationSucceeded) {
                    //  Registration of HTTP session failed for the operation
                    R failed = provider.failedAuthentication(null, "error.invalidRequest");
                    logger.info("Operation continue failed during registration of HTTP session, operation ID: {}", operationId);
                    return failed;
                }
            } else if (!operationSessionEntity.getHttpSessionId().equals(httpSessionId)){
                // Operation continue failed because operation is accessed from another HTTP session
                R failed = provider.failedAuthentication(userId, "error.invalidRequest");
                logger.info("Operation continue failed because HTTP session ID has changed, operation ID: {}", operationId);
                return failed;
            }
            final String organizationId = operation.getOrganizationId();
            filterStepsBasedOnActiveAuthMethods(operation.getSteps(), userId, operationId);
            if (!authenticationManagementService.isPendingSessionAuthenticated() && userId != null && organizationId != null) {
                authenticationManagementService.updateAuthenticationWithUserDetails(userId, organizationId);
            }
            if (AuthResult.DONE.equals(operation.getResult())) {
                R done = provider.doneAuthentication(userId);
                logger.info("Operation continue succeeded, result is DONE, operation ID: {}", operationId);
                return done;
            } else if (AuthResult.FAILED.equals(operation.getResult())) {
                R failed = provider.failedAuthentication(userId, "operation.notAvailable");
                logger.info("Operation continue succeeded, result is FAILED, operation ID: {}", operationId);
                return failed;
            } else {
                R cont = provider.continueAuthentication(operationId, userId, operation.getSteps());
                logger.info("Operation continue succeeded, result is CONTINUE, operation ID: {}", operationId);
                return cont;
            }
        } catch (AuthStepException e) {
            logger.error("Error occurred while updating operation", e);
            return provider.failedAuthentication(null, e.getMessage());
        }
    }

    /**
     * Cancel active operations within HTTP session.
     *
     * @param httpSessionId HTTP session ID.
     */
    private void cancelOperationsInHttpSession(String httpSessionId) {
        // At first cancel operations within same HTTP session in the operation to session mapping
        List<OperationSessionEntity> operationsToCancel = operationSessionService.cancelOperationsInHttpSession(httpSessionId);
        for (OperationSessionEntity operationToCancel: operationsToCancel) {
            operationCancellationService.cancelOperation(operationToCancel.getOperationId(), getAuthMethodName(), OperationCancelReason.INTERRUPTED_OPERATION);
        }
    }

    /**
     * Build next authentication step information for given operation.
     *
     * @param request  Request containing information about the current authentication.
     * @param provider Provider with authentication callback implementation.
     * @return Response indicating next step, based on provider response.
     * @throws AuthStepException In case authentication fails.
     */
    protected R buildAuthorizationResponse(T request, AuthResponseProvider provider) throws AuthStepException {
        try {
            AuthenticationResult authResult = authenticate(request);
            UpdateOperationResponse responseObject;
            String userId = null;
            String organizationId;
            if (authResult == null || authResult.getUserId() == null || authResult.getOrganizationId() == null) {
                GetOperationDetailResponse operation = getOperation();
                // user was not authenticated - fail authorization
                authenticationManagementService.clearContext();
                responseObject = failAuthorization(operation.getOperationId(), null, request.getAuthInstruments(), null);
            } else {
                userId = authResult.getUserId();
                organizationId = authResult.getOrganizationId();
                // user was authenticated - complete authorization
                String operationId = authenticationManagementService.updateAuthenticationWithUserDetails(userId, authResult.getOrganizationId());

                // response could not be derived - call authorize() method to update current operation
                responseObject = authorize(operationId, userId, organizationId, request.getAuthInstruments(), null);
            }
            // TODO: Allow passing custom parameters
            switch (responseObject.getResult()) {
                case DONE: {
                    return provider.doneAuthentication(userId);
                }
                case FAILED: {
                    return provider.failedAuthentication(userId, responseObject.getResultDescription());
                }
                case CONTINUE: {
                    return provider.continueAuthentication(responseObject.getOperationId(), userId, responseObject.getSteps());
                }
                default: {
                    return provider.failedAuthentication(userId, "error.unknown");
                }
            }
        } catch (OperationAlreadyFinishedException ex) {
            // Translate Next Step exception for update of a finished operation
            throw new OperationIsAlreadyFinished(ex.getMessage());
        } catch (OperationAlreadyCanceledException ex) {
            // Translate Next Step exception for update of a canceled operation
            throw new OperationIsAlreadyCanceledException(ex.getMessage());
        } catch (OperationAlreadyFailedException ex) {
            // Translate Next Step exception for update of a failed operation
            throw new OperationIsAlreadyFailedException(ex.getMessage());
        } catch (NextStepServiceException ex) {
            // Generic Next Step error
            logger.error("Error while building authorization response", ex);
            throw new CommunicationFailedException("Step authorization failed");
        }
    }

    /**
     * Clear current browser session.
     */
    protected void clearCurrentBrowserSession() {
        authenticationManagementService.clearContext();
    }

    /**
     * Authenticate current browser session.
     */
    protected void authenticateCurrentBrowserSession() {
        authenticationManagementService.authenticateCurrentSession();
        try {
            final GetOperationDetailResponse operation = getOperation();
            if (AuthResult.DONE.equals(operation.getResult())) {
                authenticationManagementService.pendingAuthenticationToAuthentication();
            }
        } catch (AuthStepException e) {
            logger.error("Error occurred while authenticating browser session", e);
        }
    }

    /**
     * Returns whether current authentication method is available in operation steps.
     * @param operation Operation.
     * @return Whether authentication method is available.
     */
    protected boolean isAuthMethodAvailable(GetOperationDetailResponse operation) {
        final AuthMethod currentAuthMethod = getAuthMethodName(operation);
        return isAuthMethodAvailable(operation, currentAuthMethod);
    }

    /**
     * Returns whether authentication method is available in operation steps.
     * @param operation Operation.
     * @param authMethod Authentication method.
     * @return Whether authentication method is available.
     */
    private boolean isAuthMethodAvailable(GetOperationDetailResponse operation, AuthMethod authMethod) {
        for (AuthStep step: operation.getSteps()) {
            if (step.getAuthMethod() == authMethod) {
                return true;
            }
        }
        return false;
    }

    /**
     * Validate that operation state is valid in current step.
     * @param operation Operation.
     * @throws AuthStepException Thrown when operation state is invalid.
     */
    private void validateOperationState(GetOperationDetailResponse operation) throws AuthStepException {
        if (operation == null) {
            logger.error("Operation is not available");
            throw new OperationNotAvailableException("Operation is not available");
        }
        logger.debug("Validate operation started, operation ID: {}", operation.getOperationId());
        checkOperationExpiration(operation);
        if (operation.getResult() == AuthResult.FAILED) {
            List<OperationHistory> operationHistory = operation.getHistory();
            if (operationHistory.size() == 0 || operationHistory.get(operationHistory.size()-1).getRequestAuthStepResult() != AuthStepResult.CANCELED) {
                // allow displaying of canceled operations - operation may be canceled in mobile app and later displayed in web UI
                logger.warn("Operation has already failed, operation ID: {}", operation.getOperationId());
                throw new OperationIsAlreadyFailedException("Operation has already failed");
            }
        }
        final AuthMethod currentAuthMethod = getAuthMethodName(operation);
        List<OperationHistory> operationHistoryList = operation.getHistory();
        if (operationHistoryList == null || operationHistoryList.isEmpty()) {
            logger.error("Operation is missing its history, operation ID: {}", operation.getOperationId());
            throw new OperationMissingHistoryException("Operation is missing its history");
        }
        AuthMethod chosenAuthMethod = operation.getChosenAuthMethod();
        if (chosenAuthMethod != null) {
            // check that chosen authentication method matches next steps
            if (!isAuthMethodAvailable(operation, chosenAuthMethod)) {
                logger.warn("Invalid chosen authentication method, operation ID: {}, authentication method: {}", operation.getOperationId(), chosenAuthMethod.toString());
                throw new InvalidChosenMethodException("Invalid chosen authentication method: "+chosenAuthMethod);
            }
        }
        if (operation.getResult() == AuthResult.CONTINUE) {
            // verify operation hash
            String clientOperationHash = request.getHeader("X-OPERATION-HASH");
            String currentOperationHash = operationSessionService.generateOperationHash(operation.getOperationId());
            // mobile API clients do not send operation hash - when operation hash is missing, concurrency check is not performed
            if (clientOperationHash != null && !clientOperationHash.equals(currentOperationHash)) {
                logger.warn("Operation was interrupted, operation ID: {}", operation.getOperationId());
                throw new OperationInterruptedException("Operation was interrupted");
            }
            // special handling for SHOW_OPERATION_DETAIL - endpoint can be called only when either SMS_KEY, POWERAUTH_TOKEN or LOGIN_SCA are present in next steps
            if (currentAuthMethod == AuthMethod.SHOW_OPERATION_DETAIL) {
                if (!isAuthMethodAvailable(operation, AuthMethod.SMS_KEY) && !isAuthMethodAvailable(operation, AuthMethod.POWERAUTH_TOKEN) && !isAuthMethodAvailable(operation, AuthMethod.LOGIN_SCA)) {
                    logger.warn("Authentication method is not available, operation ID: {}, authentication method: {}", operation.getOperationId(), currentAuthMethod.toString());
                    throw new AuthMethodNotAvailableException("Authentication method is not available: " + currentAuthMethod);
                }
            }
        }
        logger.debug("Operation validation succeeded, operation ID: {}", operation.getOperationId());
    }

    /**
     * Resolves the number of remaining authentication attempts.
     * @param remainingAttemptsDA Number of remaining attempts from Data Adapter.
     * @param remainingAttemptsNS Number of remaining attempts from Next Step.
     * @return Resolved number of remaining attempts. Null value is returned for no limit.
     */
    protected Integer resolveRemainingAttempts(Integer remainingAttemptsDA, Integer remainingAttemptsNS) {
        if (remainingAttemptsDA == null && remainingAttemptsNS == null) {
            // no remaining attempts are set
            return null;
        } else if (remainingAttemptsDA == null) {
            // only NS remaining attempts are set
            return remainingAttemptsNS;
        } else if (remainingAttemptsNS == null) {
            // only DA remaining attempts are set
            return remainingAttemptsDA;
        } else if (remainingAttemptsDA < remainingAttemptsNS) {
            // DA has smaller number of remaining attempts
            return remainingAttemptsDA;
        } else {
            // NS has smaller number of remaining attempts
            return remainingAttemptsNS;
        }
    }

    /**
     * Filter the list of steps based on current availability of authentication methods.
     * @param authSteps List of authentication steps.
     * @param userId User ID, use null for unknown user ID.
     * @param operationId Operation ID.
     */
    private void filterStepsBasedOnActiveAuthMethods(List<AuthStep> authSteps, String userId, String operationId) {
        Set<AuthStep> authStepsToRemove = new HashSet<>();
        for (AuthStep authStep: authSteps) {
            if (!authMethodQueryService.isAuthMethodEnabled(authStep.getAuthMethod(), userId, operationId)) {
                authStepsToRemove.add(authStep);
            }
        }
        authSteps.removeAll(authStepsToRemove);
    }

    /**
     * Update operation for SCA login in case of an approval operation.
     * @param operation Operation to update.
     */
    private void updateOperationForScaLogin(GetOperationDetailResponse operation) {
        // Convert operation definition for LOGIN_SCA step which requires login operation definition and not approval operation definition.
        // This is a temporary workaround until Web Flow supports configuration of multiple operations in a compound operation.
        if (getAuthMethodName(operation) == AuthMethod.LOGIN_SCA) {
            // Make sure Mobile Token and Data Adapter recognize the operation name
            operation.setOperationName("login");
            // Update operation data for login
            operation.setOperationData("A2");
            // Update operation form data
            OperationFormData formData = new OperationFormData();
            formData.addTitle("login.title");
            formData.addGreeting("login.greeting");
            formData.addSummary("login.summary");
            formData.setUserInput(operation.getFormData().getUserInput());
            operation.setFormData(formData);
        }
    }

    /**
     * Class providing callbacks for operation authentication outcomes.
     */
    public abstract class AuthResponseProvider {

        /**
         * Called in case user successfully authenticated and no other authentication is needed.
         *
         * @param userId User ID.
         * @return Information about successful authentication, confirmation step.
         */
        public abstract R doneAuthentication(String userId);

        /**
         * Called in case authentication fails and no other steps can be performed.
         *
         * @param userId       User ID.
         * @param failedReason Reason for the failure.
         * @return Information about authentication failure, error step.
         */
        public abstract R failedAuthentication(String userId, String failedReason);

        /**
         * Called in case authentication should continue with next step(s).
         *
         * @param operationId Operation ID of the current operation.
         * @param userId      User ID.
         * @param steps       List of next steps to be performed.
         * @return Information about next steps for given operation.
         */
        public abstract R continueAuthentication(String operationId, String userId, List<AuthStep> steps);
    }


}
