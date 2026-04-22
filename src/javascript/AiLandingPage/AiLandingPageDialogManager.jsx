/**
 * Dialog Manager — mounts the AiLandingPageDialog outside the action
 * component tree so jContent unmounting the menu doesn't destroy the dialog.
 */
import React from 'react';
import ReactDOM from 'react-dom';
import i18next from 'i18next';
import {I18nextProvider} from 'react-i18next';
import {AiLandingPageDialog} from './AiLandingPageDialog';

class AiLandingPageDialogManager {
    constructor() {
        this.container = null;
    }

    _init() {
        if (!this.container) {
            this.container = document.createElement('div');
            this.container.id = 'ailp-dialog-container';
            document.body.appendChild(this.container);
        }
    }

    open({nodePath, lang}) {
        this._init();

        const handleClose = () => {
            ReactDOM.unmountComponentAtNode(this.container);
        };

        ReactDOM.render(
            <I18nextProvider i18n={i18next}>
                <AiLandingPageDialog
                    nodePath={nodePath}
                    lang={lang}
                    onClose={handleClose}
                />
            </I18nextProvider>,
            this.container
        );
    }
}

export const dialogManager = new AiLandingPageDialogManager();
