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
import React from "react";
import {connect} from "react-redux";
// Actions
import {cancel, getOperationData, updateOperation} from "../actions/operationReviewActions";
// Components
import {Panel} from "react-bootstrap";
import Spinner from 'react-tiny-spin';
import OperationTimeout from "./operationTimeout";
// i18n
import {FormattedMessage} from "react-intl";
import OperationDetail from "./operationDetail";

/**
 * Review of operation with choice of actions for next authorization step.
 * Operation details are displayed using the OperationDetail component.
 */
@connect((store) => {
    return {
        context: store.dispatching.context
    }
})
export default class OperationReview extends React.Component {

    constructor() {
        super();
        this.handleToken = this.handleToken.bind(this);
        this.handleSMS = this.handleSMS.bind(this);
        this.handleCancel = this.handleCancel.bind(this);
        this.switchToTokenScreen = this.switchToTokenScreen.bind(this);
        this.switchToSMSScreen = this.switchToSMSScreen.bind(this);
    }

    componentWillMount() {
        this.props.dispatch(getOperationData());
    }

    handleToken(event) {
        event.preventDefault();
        const switchToTokenScreen = this.switchToTokenScreen;
        // choose authMethod and send updated formData, then move to the token screen
        if (this.props.context.formData) {
            // bank account choice is frozen
            this.props.context.formData.userInput["operation.bankAccountChoice.disabled"] = true;
            this.props.dispatch(updateOperation(this.props.context.formData, "POWERAUTH_TOKEN", function () {
                // change screen after form data and chosen authentication method are stored
                switchToTokenScreen();
            }));
        }
    }

    switchToTokenScreen() {
        this.props.dispatch({
            type: "SHOW_SCREEN_TOKEN",
            payload: {
                info: "firstLoad"
            }
        });
    }


    handleSMS(event) {
        event.preventDefault();
        const switchToSMSScreen = this.switchToSMSScreen;
        // choose authMethod and send updated formData, then move to the sms screen
        if (this.props.context.formData) {
            // bank account choice is frozen
            this.props.context.formData.userInput["operation.bankAccountChoice.disabled"] = true;
            this.props.dispatch(updateOperation(this.props.context.formData, "SMS_KEY", function () {
                // change screen after formData and chosen authentication method are stored
                switchToSMSScreen();
            }));
        }
    }

    switchToSMSScreen() {
        this.props.dispatch({
            type: "SHOW_SCREEN_SMS",
            payload: {
                info: "firstLoad"
            }
        });
    }

    handleCancel(event) {
        event.preventDefault();
        this.props.dispatch(cancel());
    }

    render() {
        if (this.props.context.formData || this.props.context.data) {
            let authMethodCounter = 0;
            return (
                <div id="operation">
                    <form>
                        <Panel>
                            <OperationTimeout timeoutCheckActive="true"/>
                            <OperationDetail/>
                            {(this.props.context.authMethods.length > 0) ? (
                                <div className="auth-actions">
                                    {(this.props.context.authMethods.length > 1) ? (
                                        <FormattedMessage id="operation.confirmationTextChoice"/>
                                    ) : (
                                        <FormattedMessage id="operation.confirmationText"/>
                                    )}
                                    <div className="buttons">
                                        {this.props.context.authMethods.map((authMethod) => {
                                            authMethodCounter++;
                                            switch (authMethod) {
                                                case "POWERAUTH_TOKEN":
                                                    return (
                                                        <div key={authMethod}>
                                                            <div className="attribute row">
                                                                <div className="col-xs-12">
                                                                    <a href="#" onClick={this.handleToken}
                                                                       className="btn btn-lg btn-success">
                                                                        <FormattedMessage id="method.powerauthToken"/>
                                                                    </a>
                                                                </div>
                                                            </div>
                                                            {(this.props.context.authMethods.length > 1 && authMethodCounter < this.props.context.authMethods.length) ? (
                                                                <FormattedMessage id="operation.methodSelectionOr"/>
                                                            ) : (
                                                                undefined
                                                            )}
                                                        </div>
                                                    );
                                                case "SMS_KEY":
                                                    return (
                                                        <div key={authMethod}>
                                                            <div className="attribute row">
                                                                <div className="col-xs-12">
                                                                    <a href="#" onClick={this.handleSMS}
                                                                       className="btn btn-lg btn-success">
                                                                        <FormattedMessage id="method.smsKey"/>
                                                                    </a>
                                                                </div>
                                                            </div>
                                                            {(this.props.context.authMethods.length > 1 && authMethodCounter < this.props.context.authMethods.length) ? (
                                                                <FormattedMessage id="operation.methodSelectionOr"/>
                                                            ) : (
                                                                undefined
                                                            )}
                                                        </div>
                                                    );
                                            }
                                        })}
                                        <div className="attribute row">
                                            <div className="col-xs-12">
                                                <a href="#" onClick={this.handleCancel} className="btn btn-lg btn-default">
                                                    <FormattedMessage id="operation.cancel"/>
                                                </a>
                                            </div>
                                        </div>
                                    </div>
                                </div>
                            ) : (
                                <div className={'message-error'}>
                                    <FormattedMessage id="operation.noMethod"/>
                                </div>
                            )}
                        </Panel>
                    </form>
                </div>
            )
        } else {
            return (
                <div id="operation">
                    <Spinner/>
                </div>
            )
        }
    }
}