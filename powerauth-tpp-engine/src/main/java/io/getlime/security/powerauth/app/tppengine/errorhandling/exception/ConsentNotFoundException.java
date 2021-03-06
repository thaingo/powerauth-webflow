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

package io.getlime.security.powerauth.app.tppengine.errorhandling.exception;

/**
 * Exception thrown on missing consent.
 *
 * @author Petr Dvorak, petr@wultra.com
 */
public class ConsentNotFoundException extends Exception {

    private final String id;
    private static final String DEFAULT_MESSAGE = "Consent with given ID was not found.";

    public ConsentNotFoundException(String id) {
        super(DEFAULT_MESSAGE);
        this.id = id;
    }

    /**
     * Get ID of the consent that was not found.
     * @return ID of the consent that was not found.
     */
    public String getId() {
        return id;
    }

}
