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
package io.getlime.security.powerauth.lib.credentials.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.HttpStatus;

/**
 * @author Roman Strobl
 */
public class AuthenticationResponseSuccess extends AuthenticationResponse {

    @JsonProperty
    private String userId;

    public AuthenticationResponseSuccess() {
    }

    public AuthenticationResponseSuccess(String userId) {
        this.httpStatus = HttpStatus.OK;
        this.status = AuthenticationStatus.SUCCESS;
        this.userId = userId;
    }

    public String getUserId() {
        return userId;
    }

}