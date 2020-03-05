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
import axios from "axios";
import {dispatchAction, dispatchError} from "../dispatcher/dispatcher";
import {handleAuthFailedError} from "./errorHandling";

/**
 * Initialize SCA approval.
 * @returns {Function} No return value.
 */
export function init(callback) {
    return function (dispatch) {
        dispatch({
            type: "SHOW_SCREEN_APPROVAL_SCA",
            payload: {
                loading: true,
                error: false,
                message: ""
            }
        });
        axios.post("./api/auth/approval-sca/init", {}).then((response) => {
            dispatch({
                type: "SHOW_SCREEN_APPROVAL_SCA",
                loading: true,
                error: false,
                payload: response.data
            });
            callback(true);
            return null;
        }).catch((error) => {
            dispatchError(dispatch, error);
        })
    }
}

/**
 * Get operation detail.
 * @returns {Function} Operation detail.
 */
export function getOperationData() {
    return function (dispatch) {
        axios.post("./api/auth/operation/detail", {}, {
            headers: {
                'X-OPERATION-HASH': operationHash,
            }
        }).then((response) => {
            dispatch({
                type: "SHOW_SCREEN_APPROVAL_SCA",
                loading: false,
                error: false,
                payload: response.data
            });
            return null;
        }).catch((error) => {
            dispatchError(dispatch, error);
        })
    }
}

/**
 * Confirm SCA approval.
 * @returns {Function} No return value.
 */
export function confirm() {
    return function (dispatch) {
        dispatch({
            type: "SHOW_SCREEN_APPROVAL_SCA",
            payload: {
                loading: true,
                error: false,
                message: ""
            }
        });
        axios.post("./api/auth/approval-sca/authenticate", {}, {
            headers: {
                'X-OPERATION-HASH': operationHash,
            }
        }).then((response) => {
            switch (response.data.result) {
                case 'CONFIRMED': {
                    if (response.data.mobileTokenEnabled) {
                        dispatch({
                            type: "SHOW_SCREEN_TOKEN",
                            payload: {
                                loading: true,
                                error: false,
                                message: "",
                                smsFallbackAvailable: true
                            }
                        });
                    } else {
                        dispatch({
                            type: "SHOW_SCREEN_SMS",
                            payload: {
                                loading: true,
                                error: false,
                                message: ""
                            }
                        });
                    }
                    break;
                }
                case 'AUTH_FAILED': {
                    if (!handleAuthFailedError(dispatch, response)) {
                        dispatch({
                            type: "SHOW_SCREEN_APPROVAL_SCA",
                            payload: {
                                loading: false,
                                error: true,
                                message: response.data.message
                            }
                        });
                    }
                    break;
                }
            }
            return null;
        }).catch((error) => {
            dispatchError(dispatch, error);
        })
    }
}

/**
 * Cancel SCA approval.
 * @returns {Function} No return value.
 */
export function cancel() {
    return function (dispatch) {
        axios.post("./api/auth/approval-sca/cancel", {}, {
            headers: {
                'X-OPERATION-HASH': operationHash,
            }
        }).then((response) => {
            dispatch({
                type: "SHOW_SCREEN_ERROR",
                payload: {
                    message: response.data.message
                }
            });
            return null;
        }).catch((error) => {
            dispatchError(dispatch, error);
        })
    }
}

/**
 * Verify client TLS certificate.
 * @param certificateVerificationUrl URL to be used to verify client TLS certificate.
 * @param callbackOnSuccess Callback in case of successful verification.
 * @returns {Function} No return value.
 */
export function checkClientCertificate(certificateVerificationUrl, callbackOnSuccess) {
    return function (dispatch) {
        axios.post(certificateVerificationUrl, {}, {
            // Send cookies so that HTTP session is the same
            withCredentials: true
        }).then((response) => {
            callbackOnSuccess();
            return null;
        }).catch((error) => {
            // Convert error message to a user friendly error message
            dispatch({
                type: "SHOW_SCREEN_APPROVAL_SCA",
                payload: {
                    loading: false,
                    error: true,
                    message: "clientCertificate.failed"
                }
            });
            return null;
        })
    }
}