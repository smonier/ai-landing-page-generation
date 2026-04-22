import React from 'react';
import {LayoutContent} from '@jahia/moonstone';
import {useTranslation} from 'react-i18next';
export const AdminPanel = () => {
    const {t} = useTranslation('ai-landing-page-generation');

    return (
        <LayoutContent content={(
            <>
                {t('ai-landing-page-generation.hello')} {window.contextJsParameters.currentUser} !
            </>
    )}/>
    );
};
