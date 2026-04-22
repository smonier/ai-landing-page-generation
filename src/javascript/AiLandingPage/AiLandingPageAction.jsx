import React from 'react';
import PropTypes from 'prop-types';
import {useNodeChecks} from '@jahia/data-helper';
import {dialogManager} from './AiLandingPageDialogManager';

export const AiLandingPageAction = ({path, language, render: Render, ...otherProps}) => {
    // JContent doesn't always pass `language` in contentActions context — fall
    // back to the language segment in the jContent URL (/jahia/jcontent/{site}/{lang}/...)
    const lang = language || window.location.pathname.split('/')[4] || 'en';

    const {checksResult} = useNodeChecks({path}, {
        showOnNodeTypes: ['jnt:page', 'jnt:navMenuText'],
        requiredPermission: ['jcr:write_default']
    });

    if (!Render || !checksResult) {
        return null;
    }

    return (
        <Render
            {...otherProps}
            onClick={() => dialogManager.open({nodePath: path, lang})}
        />
    );
};

AiLandingPageAction.propTypes = {
    path: PropTypes.string.isRequired,
    language: PropTypes.string,
    render: PropTypes.func
};

