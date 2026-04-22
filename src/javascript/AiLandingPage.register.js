import {registry} from '@jahia/ui-extender';
import i18next from 'i18next';
import React from 'react';
import {WebPage} from '@jahia/moonstone';
import {AiLandingPageAction} from './AiLandingPage';

export default async function () {
    await i18next.loadNamespaces('ai-landing-page-generation');
    console.debug('%c ai-landing-page-generation actions registered', 'color: #3c8cba');
    registry.add('action', 'aiLandingPageGenerate', {
        targets: ['contentActions:999'],
        buttonLabel: 'ai-landing-page-generation:ai-landing-page-generation.action.label',
        buttonIcon: <WebPage/>,
        showOnNodeTypes: ['jnt:page', 'jnt:navMenuText'],
        requiredPermission: 'jcr:write_default',
        isModal: true,
        component: AiLandingPageAction
    });
}
