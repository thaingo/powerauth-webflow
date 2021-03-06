/*
 * Copyright 2019 Wultra s.r.o.
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
package io.getlime.security.powerauth.lib.nextstep.model.request;

import io.getlime.security.powerauth.lib.nextstep.model.entity.enumeration.UserAccountStatus;

/**
 * Request object used for updating a user of an operation.
 *
 * @author Roman Strobl, roman.strobl@wultra.com
 */
public class UpdateOperationUserRequest {

    private String operationId;
    private String userId;
    private String organizationId;
    private UserAccountStatus accountStatus;

    /**
     * Default constructor.
     */
    public UpdateOperationUserRequest() {
    }

    /**
     * Constructor with all details.
     * @param operationId Operation ID.
     * @param userId User ID.
     * @param organizationId Organization ID.
     * @param accountStatus User account status.
     */
    public UpdateOperationUserRequest(String operationId, String userId, String organizationId, UserAccountStatus accountStatus) {
        this.operationId = operationId;
        this.userId = userId;
        this.organizationId = organizationId;
        this.accountStatus = accountStatus;
    }

    /**
     * Get operation ID.
     * @return Operation ID.
     */
    public String getOperationId() {
        return operationId;
    }

    /**
     * Set operation ID.
     * @param operationId Operation ID.
     */
    public void setOperationId(String operationId) {
        this.operationId = operationId;
    }

    /**
     * Get user ID of the user who is associated with the operation.
     * @return User ID.
     */
    public String getUserId() {
        return userId;
    }

    /**
     * Set user ID of the user who is associated with the operation.
     * @param userId User ID.
     */
    public void setUserId(String userId) {
        this.userId = userId;
    }

    /**
     * Get organization ID.
     * @return Organization ID.
     */
    public String getOrganizationId() {
        return organizationId;
    }

    /**
     * Set organization ID.
     * @param organizationId Organization ID.
     */
    public void setOrganizationId(String organizationId) {
        this.organizationId = organizationId;
    }

    /**
     * Get current user account status.
     * @return User account status.
     */
    public UserAccountStatus getAccountStatus() {
        return accountStatus;
    }

    /**
     * Set current user account status.
     * @param accountStatus User account status.
     */
    public void setAccountStatus(UserAccountStatus accountStatus) {
        this.accountStatus = accountStatus;
    }
}
