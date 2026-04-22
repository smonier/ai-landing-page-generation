import './publicPath';
import {registry} from '@jahia/ui-extender';
import registerAiLandingPage from './AiLandingPage.register';

export default function () {
    registry.add('callback', 'ai-landing-page-generation-actions', {
        targets: ['jahiaApp-init:60'],
        callback: registerAiLandingPage
    });
}
