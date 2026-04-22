import React, {useCallback, useEffect, useReducer, useRef, useState} from 'react';
import PropTypes from 'prop-types';
import {useTranslation} from 'react-i18next';
import {
    Box,
    Button,
    CircularProgress,
    Dialog,
    DialogActions,
    DialogContent,
    DialogTitle,
    FormControl,
    InputLabel,
    LinearProgress,
    Select,
    TextField,
    Typography
} from '@mui/material';
import {generatePage, getConfig, getTemplates, materializePage} from './AiLandingPage.utils';

// ── Dialog steps ──────────────────────────────────────────────────────────────
const STEP_FORM = 'FORM';
const STEP_LOADING = 'LOADING';
const STEP_PREVIEW = 'PREVIEW';
const STEP_SUCCESS = 'SUCCESS';

// ── State shape ───────────────────────────────────────────────────────────────
const initialState = {
    step: STEP_FORM,
    prompt: '',
    audience: '',
    tone: '',
    documentFile: null,
    urlsRaw: '',
    structureJson: null,
    pageTitle: '',
    templateName: '',
    error: null
};

function reducer(state, action) {
    switch (action.type) {
        case 'SET_FIELD':
            return {...state, [action.field]: action.value};
        case 'SET_DOCUMENT':
            return {
                ...state,
                documentFile: action.file
            };
        case 'GENERATING':
            return {...state, step: STEP_LOADING, error: null};
        case 'PREVIEW':
            return {...state, step: STEP_PREVIEW, structureJson: action.structureJson, error: null};
        case 'ERROR':
            return {...state, step: STEP_FORM, error: action.error};
        case 'SUCCESS':
            return {...state, step: STEP_SUCCESS, error: null};
        case 'RESET':
            return {...initialState};
        default:
            return state;
    }
}

// ── Default values (match AI_AUDIENCES / AI_TONES defaults in .cfg) ───────────
const DEFAULT_AUDIENCES = ['IT', 'Finance', 'Marketing', 'Sales', 'HR', 'C-Suite', 'General Public', 'Students', 'Developers'];
const DEFAULT_TONES = ['Professional', 'Friendly', 'Bold', 'Playful', 'Authoritative', 'Witty', 'Inspirational', 'Concise', 'Verbose'];

// ── Component ─────────────────────────────────────────────────────────────────
export const AiLandingPageDialog = ({nodePath, lang, onClose}) => {
    const {t} = useTranslation('ai-landing-page-generation');
    const [state, dispatch] = useReducer(reducer, initialState);
    const fileInputRef = useRef(null);
    const [audiences, setAudiences] = useState(DEFAULT_AUDIENCES);
    const [tones, setTones] = useState(DEFAULT_TONES);
    const [templates, setTemplates] = useState([]);

    useEffect(() => {
        getConfig(nodePath, lang)
            .then(cfg => {
                if (cfg.audiences && cfg.audiences.length > 0) {
                    setAudiences(cfg.audiences);
                }

                if (cfg.tones && cfg.tones.length > 0) {
                    setTones(cfg.tones);
                }
            })
            .catch(() => { /* keep defaults */ });

        getTemplates(nodePath, lang)
            .then(list => {
                if (list.length > 0) {
                    setTemplates(list);
                }
            })
            .catch(() => { /* no templates */ });
    }, [nodePath, lang]);

    // ── Handlers ─────────────────────────────────────────────────────────────
    const handleFileChange = useCallback(e => {
        const file = e.target.files?.[0];
        if (!file) {
            return;
        }

        if (file.size > 10 * 1024 * 1024) {
            dispatch({type: 'SET_FIELD', field: 'error', value: 'Document exceeds 10 MB limit.'});
            return;
        }

        dispatch({type: 'SET_DOCUMENT', file});
    }, []);

    const handleGenerate = useCallback(async () => {
        if (!state.prompt.trim()) {
            dispatch({type: 'SET_FIELD', field: 'error', value: 'Please enter a prompt.'});
            return;
        }

        if (!state.audience) {
            dispatch({type: 'SET_FIELD', field: 'error', value: 'Please select a target audience.'});
            return;
        }

        if (!state.tone) {
            dispatch({type: 'SET_FIELD', field: 'error', value: 'Please select a tone.'});
            return;
        }

        dispatch({type: 'GENERATING'});
        try {
            const result = await generatePage({
                nodePath,
                lang,
                prompt: state.prompt,
                audience: state.audience,
                tone: state.tone,
                documentFile: state.documentFile || null,
                urls: state.urlsRaw || null
            });

            if (!result.success) {
                const key = result.rateLimited ?
                    'ai-landing-page-generation.dialog.error.rateLimit' :
                    'ai-landing-page-generation.dialog.error.generic';
                dispatch({type: 'ERROR', error: t(key)});
            } else {
                dispatch({type: 'PREVIEW', structureJson: result.structureJson});
            }
        } catch (_) {
            dispatch({type: 'ERROR', error: t('ai-landing-page-generation.dialog.error.generic')});
        }
    }, [state, nodePath, lang, t]);

    const handleAccept = useCallback(async () => {
        if (!state.pageTitle.trim()) {
            dispatch({type: 'SET_FIELD', field: 'error', value: 'Please enter a page title.'});
            return;
        }

        if (!state.templateName) {
            dispatch({type: 'SET_FIELD', field: 'error', value: 'Please select a page template.'});
            return;
        }

        dispatch({type: 'GENERATING'});
        try {
            const result = await materializePage({
                nodePath,
                lang,
                structureJson: state.structureJson,
                pageTitle: state.pageTitle,
                templateName: state.templateName
            });

            if (!result.success) {
                dispatch({type: 'ERROR', error: result.error || t('ai-landing-page-generation.dialog.error.generic')});
            } else {
                dispatch({type: 'SUCCESS'});
            }
        } catch (_) {
            dispatch({type: 'ERROR', error: t('ai-landing-page-generation.dialog.error.generic')});
        }
    }, [state, nodePath, lang, t]);

    const handleRegenerate = useCallback(() => {
        dispatch({type: 'SET_FIELD', field: 'step', value: STEP_FORM});
    }, []);

    const isLoading = state.step === STEP_LOADING;

    // ── Render ────────────────────────────────────────────────────────────────
    return (
        <Dialog open fullWidth maxWidth="md" onClose={onClose}>
            <DialogTitle>
                {t('ai-landing-page-generation.dialog.title')}
            </DialogTitle>

            <DialogContent dividers>
                {/* ── FORM step ──────────────────────────────────────────── */}
                {(state.step === STEP_FORM || isLoading) && (
                    <Box sx={{display: 'flex', flexDirection: 'column', gap: 2, pt: 1}}>
                        {state.error && (
                            <Typography color="error" variant="body2">{state.error}</Typography>
                        )}

                        <TextField
                            multiline
                            fullWidth
                            label={t('ai-landing-page-generation.dialog.prompt.label')}
                            placeholder={t('ai-landing-page-generation.dialog.prompt.placeholder')}
                            value={state.prompt}
                            rows={5}
                            disabled={isLoading}
                            onChange={e => dispatch({type: 'SET_FIELD', field: 'prompt', value: e.target.value})}
                        />

                        <Box sx={{display: 'flex', gap: 2}}>
                            <FormControl fullWidth disabled={isLoading}>
                                <InputLabel>{t('ai-landing-page-generation.dialog.audience.label')}</InputLabel>
                                <Select
                                    native
                                    value={state.audience}
                                    label={t('ai-landing-page-generation.dialog.audience.label')}
                                    onChange={e => dispatch({type: 'SET_FIELD', field: 'audience', value: e.target.value})}
                                >
                                    <option value=""/>
                                    {audiences.map(a => (
                                        <option key={a} value={a}>{a}</option>
                                    ))}
                                </Select>
                            </FormControl>

                            <FormControl fullWidth disabled={isLoading}>
                                <InputLabel>{t('ai-landing-page-generation.dialog.tone.label')}</InputLabel>
                                <Select
                                    native
                                    value={state.tone}
                                    label={t('ai-landing-page-generation.dialog.tone.label')}
                                    onChange={e => dispatch({type: 'SET_FIELD', field: 'tone', value: e.target.value})}
                                >
                                    <option value=""/>
                                    {tones.map(tone => (
                                        <option key={tone} value={tone}>{tone}</option>
                                    ))}
                                </Select>
                            </FormControl>
                        </Box>

                        <Box>
                            <Typography variant="caption" color="text.secondary">
                                {t('ai-landing-page-generation.dialog.document.label')}
                            </Typography>
                            <Box sx={{mt: 0.5}}>
                                <input
                                    ref={fileInputRef}
                                    type="file"
                                    accept=".pdf,.docx,.txt,.md,text/plain,text/markdown,application/pdf,application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                                    disabled={isLoading}
                                    onChange={handleFileChange}
                                />
                            </Box>
                            {state.documentFile && (
                                <Typography variant="caption">{state.documentFile.name}</Typography>
                            )}
                        </Box>

                        <TextField
                            fullWidth
                            label={t('ai-landing-page-generation.dialog.urls.label')}
                            value={state.urlsRaw}
                            disabled={isLoading}
                            onChange={e => dispatch({type: 'SET_FIELD', field: 'urlsRaw', value: e.target.value})}
                        />

                        {isLoading && (
                            <Box sx={{display: 'flex', alignItems: 'center', gap: 2, mt: 1}}>
                                <CircularProgress size={20}/>
                                <Typography variant="body2">
                                    {t('ai-landing-page-generation.dialog.generating')}
                                </Typography>
                            </Box>
                        )}
                        {isLoading && <LinearProgress/>}
                    </Box>
                )}

                {/* ── PREVIEW step ───────────────────────────────────────── */}
                {state.step === STEP_PREVIEW && (
                    <Box sx={{display: 'flex', flexDirection: 'column', gap: 2, pt: 1}}>
                        <Typography variant="subtitle1" fontWeight={600}>
                            {t('ai-landing-page-generation.dialog.preview.title')}
                        </Typography>

                        <StructurePreview structureJson={state.structureJson}/>

                        <FormControl fullWidth>
                            <InputLabel>{t('ai-landing-page-generation.dialog.template.label')}</InputLabel>
                            <Select
                                native
                                value={state.templateName}
                                label={t('ai-landing-page-generation.dialog.template.label')}
                                onChange={e => dispatch({type: 'SET_FIELD', field: 'templateName', value: e.target.value})}
                            >
                                <option value=""/>
                                {templates.map(tpl => (
                                    <option key={tpl.name} value={tpl.name}>{tpl.title}</option>
                                ))}
                            </Select>
                        </FormControl>

                        <TextField
                            fullWidth
                            label="Page Title"
                            value={state.pageTitle}
                            onChange={e => dispatch({type: 'SET_FIELD', field: 'pageTitle', value: e.target.value})}
                        />

                        {state.error && (
                            <Typography color="error" variant="body2">{state.error}</Typography>
                        )}
                    </Box>
                )}

                {/* ── SUCCESS step ───────────────────────────────────────── */}
                {state.step === STEP_SUCCESS && (
                    <Typography color="success.main" variant="body1" sx={{pt: 1}}>
                        {t('ai-landing-page-generation.dialog.success')}
                    </Typography>
                )}
            </DialogContent>

            <DialogActions>
                {state.step === STEP_FORM && (
                    <>
                        <Button onClick={onClose}>
                            {t('ai-landing-page-generation.dialog.cancel.button')}
                        </Button>
                        <Button variant="contained" onClick={handleGenerate}>
                            {t('ai-landing-page-generation.dialog.generate.button')}
                        </Button>
                    </>
                )}
                {isLoading && (
                    <Button onClick={onClose}>
                        {t('ai-landing-page-generation.dialog.cancel.button')}
                    </Button>
                )}
                {state.step === STEP_PREVIEW && (
                    <>
                        <Button onClick={handleRegenerate}>
                            {t('ai-landing-page-generation.dialog.regenerate.button')}
                        </Button>
                        <Button variant="contained" onClick={handleAccept}>
                            {t('ai-landing-page-generation.dialog.accept.button')}
                        </Button>
                    </>
                )}
                {state.step === STEP_SUCCESS && (
                    <Button variant="contained" onClick={onClose}>
                        {t('ai-landing-page-generation.dialog.close.button')}
                    </Button>
                )}
            </DialogActions>
        </Dialog>
    );
};

AiLandingPageDialog.propTypes = {
    nodePath: PropTypes.string.isRequired,
    lang: PropTypes.string.isRequired,
    onClose: PropTypes.func.isRequired
};

// ── StructurePreview ──────────────────────────────────────────────────────────
const StructurePreview = ({structureJson}) => {
    let structure;
    try {
        structure = JSON.parse(structureJson);
    } catch {
        return <Box component="pre" sx={{fontSize: '0.75rem', overflowX: 'auto'}}>{structureJson}</Box>;
    }

    return (
        <Box sx={{border: '1px solid', borderColor: 'divider', borderRadius: 1, p: 1.5, maxHeight: 300, overflowY: 'auto'}}>
            <ComponentNode node={structure} depth={0}/>
        </Box>
    );
};

const ComponentNode = ({node, depth}) => (
    <Box sx={{pl: depth * 2}}>
        <Typography variant="caption" color="primary" fontWeight={600}>{node.type}</Typography>
        {' '}
        <Typography variant="caption" color="text.secondary">
            {node.title || node.headline || node.label || ''}
        </Typography>
        {node.columns && (
            <Typography variant="caption" color="text.disabled"> — {node.columns} col(s)</Typography>
        )}
        {node.children?.map((child, i) => (
            // eslint-disable-next-line react/no-array-index-key
            <ComponentNode key={i} node={child} depth={depth + 1}/>
        ))}
    </Box>
);

StructurePreview.propTypes = {
    structureJson: PropTypes.string
};

ComponentNode.propTypes = {
    node: PropTypes.shape({
        type: PropTypes.string,
        title: PropTypes.string,
        headline: PropTypes.string,
        label: PropTypes.string,
        columns: PropTypes.oneOfType([PropTypes.string, PropTypes.number]),
        children: PropTypes.array
    }).isRequired,
    depth: PropTypes.number.isRequired
};
