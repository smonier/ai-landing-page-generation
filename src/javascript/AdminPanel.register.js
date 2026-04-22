import {registerRoutes} from './AdminPanel/AdminPanel.routes';
import i18next from 'i18next';

export default async function () {
    await i18next.loadNamespaces('ai-landing-page-generation');

    registerRoutes();
}
