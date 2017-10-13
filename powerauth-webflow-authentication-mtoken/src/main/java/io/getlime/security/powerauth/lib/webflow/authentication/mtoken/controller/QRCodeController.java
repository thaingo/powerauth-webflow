/*
 * Copyright 2017 Lime - HighTech Solutions s.r.o.
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

package io.getlime.security.powerauth.lib.webflow.authentication.mtoken.controller;

import com.google.common.io.BaseEncoding;
import io.getlime.powerauth.soap.*;
import io.getlime.security.powerauth.http.PowerAuthHttpBody;
import io.getlime.security.powerauth.lib.nextstep.client.NextStepServiceException;
import io.getlime.security.powerauth.lib.nextstep.model.entity.*;
import io.getlime.security.powerauth.lib.nextstep.model.enumeration.AuthMethod;
import io.getlime.security.powerauth.lib.nextstep.model.enumeration.AuthResult;
import io.getlime.security.powerauth.lib.nextstep.model.enumeration.AuthStepResult;
import io.getlime.security.powerauth.lib.nextstep.model.enumeration.OperationCancelReason;
import io.getlime.security.powerauth.lib.nextstep.model.response.GetOperationDetailResponse;
import io.getlime.security.powerauth.lib.nextstep.model.response.UpdateOperationResponse;
import io.getlime.security.powerauth.lib.webflow.authentication.controller.AuthMethodController;
import io.getlime.security.powerauth.lib.webflow.authentication.exception.AuthStepException;
import io.getlime.security.powerauth.lib.webflow.authentication.mtoken.exception.QRCodeInvalidDataException;
import io.getlime.security.powerauth.lib.webflow.authentication.mtoken.model.entity.ActivationEntity;
import io.getlime.security.powerauth.lib.webflow.authentication.mtoken.model.entity.QRCodeEntity;
import io.getlime.security.powerauth.lib.webflow.authentication.mtoken.model.request.QRCodeAuthenticationRequest;
import io.getlime.security.powerauth.lib.webflow.authentication.mtoken.model.request.QRCodeInitRequest;
import io.getlime.security.powerauth.lib.webflow.authentication.mtoken.model.response.QRCodeAuthenticationResponse;
import io.getlime.security.powerauth.lib.webflow.authentication.mtoken.model.response.QRCodeInitResponse;
import io.getlime.security.powerauth.soap.spring.client.PowerAuthServiceClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import java.io.IOException;
import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Controller for offline authorization based on a QR code.
 *
 * @author Roman Strobl, roman.strobl@lime-company.eu
 */
@Controller
@RequestMapping(value = "/api/auth/qr")
public class QRCodeController extends AuthMethodController<QRCodeAuthenticationRequest, QRCodeAuthenticationResponse, AuthStepException> {

    private PowerAuthServiceClient powerAuthServiceClient;

    @Autowired
    public QRCodeController(PowerAuthServiceClient powerAuthServiceClient) {
        this.powerAuthServiceClient = powerAuthServiceClient;
    }

    /**
     * Verifies the authorization code.
     *
     * @param request Request with authentication object information.
     * @return User ID if successfully authorized, otherwise null.
     * @throws AuthStepException Thrown when authorization step fails.
     */
    @Override
    protected String authenticate(@RequestBody QRCodeAuthenticationRequest request) throws AuthStepException {
        // nonce and dataHash are received from UI - they were stored together with the QR code
        String nonce = request.getNonce();
        String dataHash = request.getDataHash();
        String data = PowerAuthHttpBody.getSignatureBaseString("POST", "/operation/authorize/offline", BaseEncoding.base64().decode(nonce), BaseEncoding.base64().decode(dataHash));
        VerifyOfflineSignatureResponse signatureResponse = powerAuthServiceClient.verifyOfflineSignature(request.getActivationId(), data, request.getAuthCode(), SignatureType.POSSESSION_KNOWLEDGE);
        if (signatureResponse.isSignatureValid()) {
            String userId = getOperation().getUserId();
            if (signatureResponse.getUserId().equals(userId)) {
                return userId;
            }
        }
        // otherwise fail authorization
        try {
            UpdateOperationResponse response = failAuthorization(getOperation().getOperationId(), getOperation().getUserId(), null);
            if (response.getResult() == AuthResult.FAILED) {
                // FAILED result instead of CONTINUE means the authentication method is failed
                throw new AuthStepException("authentication.maxAttemptsExceeded", null);

            }
        } catch (NextStepServiceException e) {
            throw new AuthStepException(e.getError().getMessage(), e);
        }
        throw new AuthStepException("qrCode.invalidAuthCode", null);
    }

    @Override
    protected AuthMethod getAuthMethodName() {
        return AuthMethod.POWERAUTH_TOKEN;
    }

    /**
     * Generates the QR code to be displayed to the user.
     *
     * @return Response with QR code as String-based PNG image.
     * @throws IOException Thrown when generating QR code fails.
     */
    @RequestMapping(value = "/init", method = RequestMethod.POST)
    @ResponseBody
    public QRCodeInitResponse initQRCode(@RequestBody QRCodeInitRequest request) throws IOException, QRCodeInvalidDataException {
        QRCodeInitResponse initResponse = new QRCodeInitResponse();

        // loading of activations
        List<GetActivationListForUserResponse.Activations> allActivations = powerAuthServiceClient.getActivationListForUser(getOperation().getUserId());

        // sort activations by last timestamp used
        allActivations.sort((a1, a2) -> {
            Date timestamp1 = a1.getTimestampLastUsed().toGregorianCalendar().getTime();
            Date timestamp2 = a2.getTimestampLastUsed().toGregorianCalendar().getTime();
            return timestamp2.compareTo(timestamp1);
        });

        // transfer activations into ActivationEntity list and filter data
        List<ActivationEntity> activationEntities = new ArrayList<>();
        for (GetActivationListForUserResponse.Activations activation: allActivations) {
            if (activation.getActivationStatus() == ActivationStatus.ACTIVE) {
                ActivationEntity activationEntity = new ActivationEntity();
                activationEntity.setActivationId(activation.getActivationId());
                activationEntity.setActivationName(activation.getActivationName());
                SimpleDateFormat formatter = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
                Date timestampLastUsed = activation.getTimestampLastUsed().toGregorianCalendar().getTime();
                activationEntity.setTimestampLastUsed(formatter.format(timestampLastUsed));
                activationEntities.add(activationEntity);
            }
        }

        if (activationEntities.isEmpty()) {
            // unexpected state - last activation was removed or blocked
            throw new QRCodeInvalidDataException("qrCode.noActivation");
        }

        ActivationEntity chosenActivation = null;
        if (request.getActivationId() != null) {
            // search for requested activation
            for (ActivationEntity activationEntity: activationEntities) {
                if (request.getActivationId().equals(activationEntity.getActivationId())) {
                    chosenActivation = activationEntity;
                }
            }
        }
        if (chosenActivation == null) {
            // first activation is chosen in case activation hasn't been chosen yet
            chosenActivation = activationEntities.get(0);
        }
        // generating of QR code
        QRCodeEntity qrCodeEntity = generateQRCode(chosenActivation);
        initResponse.setQRCode(qrCodeEntity.generateImage());
        initResponse.setNonce(qrCodeEntity.getNonce());
        initResponse.setDataHash(qrCodeEntity.getDataHash());
        initResponse.setChosenActivation(chosenActivation);
        initResponse.setActivations(activationEntities);
        return initResponse;
    }

    /**
     * Performs the authorization and resolves the next step.
     *
     * @param request Request to verify authorization code based on QR code.
     * @return Authorization response.
     */
    @RequestMapping(value = "/authenticate", method = RequestMethod.POST)
    @ResponseBody
    public QRCodeAuthenticationResponse verifyAuthCode(@RequestBody QRCodeAuthenticationRequest request) {
        try {
            return buildAuthorizationResponse(request, new AuthResponseProvider() {

                @Override
                public QRCodeAuthenticationResponse doneAuthentication(String userId) {
                    authenticateCurrentBrowserSession();
                    final QRCodeAuthenticationResponse response = new QRCodeAuthenticationResponse();
                    response.setResult(AuthStepResult.CONFIRMED);
                    response.setMessage("authentication.success");
                    return response;
                }

                @Override
                public QRCodeAuthenticationResponse failedAuthentication(String userId, String failedReason) {
                    clearCurrentBrowserSession();
                    final QRCodeAuthenticationResponse response = new QRCodeAuthenticationResponse();
                    response.setResult(AuthStepResult.AUTH_FAILED);
                    response.setMessage(failedReason);
                    return response;
                }

                @Override
                public QRCodeAuthenticationResponse continueAuthentication(String operationId, String userId, List<AuthStep> steps) {
                    final QRCodeAuthenticationResponse response = new QRCodeAuthenticationResponse();
                    response.setResult(AuthStepResult.CONFIRMED);
                    response.setMessage("authentication.success");
                    response.getNext().addAll(steps);
                    return response;
                }
            });
        } catch (AuthStepException e) {
            final QRCodeAuthenticationResponse response = new QRCodeAuthenticationResponse();
            response.setResult(AuthStepResult.AUTH_FAILED);
            response.setMessage(e.getMessage());
            return response;
        }
    }

    /**
     * Cancels the QR code authorization.
     *
     * @return Authorization response.
     */
    @RequestMapping(value = "/cancel", method = RequestMethod.POST)
    @ResponseBody
    public QRCodeAuthenticationResponse cancelAuthentication() {
        try {
            cancelAuthorization(getOperation().getOperationId(), null, OperationCancelReason.UNKNOWN, null);
            final QRCodeAuthenticationResponse response = new QRCodeAuthenticationResponse();
            response.setResult(AuthStepResult.CANCELED);
            response.setMessage("operation.canceled");
            return response;
        } catch (NextStepServiceException e) {
            final QRCodeAuthenticationResponse response = new QRCodeAuthenticationResponse();
            response.setResult(AuthStepResult.AUTH_FAILED);
            response.setMessage(e.getMessage());
            return response;
        }
    }

    /**
     * Generates the QR code based on operation data.
     *
     * @return QR code as String-based PNG image.
     * @throws IOException Thrown when generating QR code fails.
     */
    private QRCodeEntity generateQRCode(ActivationEntity activation) throws IOException, QRCodeInvalidDataException {
        GetOperationDetailResponse operation = getOperation();
        String operationData = operation.getOperationData();
        String messageText = generateMessageText(operation.getFormData());

        CreateOfflineSignaturePayloadResponse response = powerAuthServiceClient.createOfflineSignaturePayload(activation.getActivationId(), operationData, messageText);

        if (!response.getData().equals(operationData)) {
            throw new QRCodeInvalidDataException("qrCode.invalidData");
        }
        // do not check message, some sanitization could be done by PowerAuth server

        QRCodeEntity qrCode = new QRCodeEntity(250);
        qrCode.setDataHash(response.getDataHash());
        qrCode.setNonce(response.getNonce());
        qrCode.setMessage(response.getMessage());
        qrCode.setSignature(response.getSignature());
        return qrCode;
    }

    /**
     * Generates the localized message for operation data.
     *
     * @param formData Operation form data.
     * @return Localized message.
     * @throws IOException Thrown when generating message fails.
     */
    private String generateMessageText(OperationFormData formData) throws IOException, QRCodeInvalidDataException {
        BigDecimal amount = null;
        String currency = null;
        String account = null;
        for (OperationFormAttribute attribute: formData.getParameters()) {
            switch (attribute.getType()) {
                case AMOUNT:
                    OperationAmountAttribute amountAttribute = (OperationAmountAttribute) attribute;
                    amount = amountAttribute.getAmount();
                    currency = amountAttribute.getCurrency();
                    break;
                case KEY_VALUE:
                    OperationKeyValueAttribute keyValueAttribute = (OperationKeyValueAttribute) attribute;
                    if ("To Account".equals(keyValueAttribute.getLabel())) {
                        account = keyValueAttribute.getValue();
                    }
                    break;
            }
        }
        if (amount==null || amount.doubleValue()<=0) {
            throw new QRCodeInvalidDataException("qrCode.invalidAmount");
        }
        if (currency==null || currency.isEmpty()) {
            throw new QRCodeInvalidDataException("qrCode.invalidCurrency");
        }
        if (account==null || account.isEmpty()) {
            throw new QRCodeInvalidDataException("qrCode.invalidAccount");
        }
        String[] messageArgs = {amount.toPlainString(), currency, account};
        return messageSource().getMessage("qrCode.messageText", messageArgs, LocaleContextHolder.getLocale());
    }

    /**
     * Get MessageSource with i18n data for authorizations SMS messages.
     *
     * @return MessageSource.
     */
    @Bean
    private MessageSource messageSource() {
        ReloadableResourceBundleMessageSource messageSource = new ReloadableResourceBundleMessageSource();
        messageSource.setBasename("classpath:/static/resources/messages");
        messageSource.setDefaultEncoding("UTF-8");
        return messageSource;
    }

}
