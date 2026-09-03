(function () {
  'use strict';

  function parseJson(raw, fallback) {
    try {
      return JSON.parse(raw);
    } catch (_) {
      return fallback;
    }
  }

  function clone(value) {
    if (value == null) return value;
    return parseJson(JSON.stringify(value), value);
  }

  var rpcSequence = 0;
  var pendingRpc = new Map();
  function hostCall(method, params) {
    var requestId = 'web-' + Date.now().toString(36) + '-' + (++rpcSequence).toString(36);
    return new Promise(function (resolve, reject) {
      pendingRpc.set(requestId, { resolve: resolve, reject: reject });
      try {
        FawnBridge.call(requestId, String(method), JSON.stringify(params == null ? {} : params));
      } catch (error) {
        pendingRpc.delete(requestId);
        reject(error);
      }
    });
  }
  window.__fawnResolve = function (requestId, ok, payload) {
    var pending = pendingRpc.get(requestId);
    if (!pending) return;
    pendingRpc.delete(requestId);
    if (ok) pending.resolve(parseJson(payload, payload));
    else pending.reject(new Error(String(payload || 'frontend-rpc-failed')));
  };
  window.__fawnEmitHostEvent = function (type, payload, sequence) {
    var value = parseJson(payload, {});
    if (value && typeof value === 'object') value.sequence = sequence;
    if (value && Object.prototype.hasOwnProperty.call(value, 'message_id')) eventEmit(type, value.message_id, value);
    else eventEmit(type, value);
  };

  function normalizeOptions(options) {
    if (typeof options === 'string') return { type: options };
    return options && typeof options === 'object' ? options : { type: 'chat' };
  }

  function variableScope(options) {
    var type = String(normalizeOptions(options).type || 'chat').toLowerCase();
    return ['global', 'chat', 'message', 'character', 'preset', 'script'].indexOf(type) >= 0 ? type : '';
  }

  function pathParts(path) {
    if (Array.isArray(path)) return path.map(String);
    return String(path || '')
      .replace(/\[(?:"([^"]*)"|'([^']*)'|(\d+))\]/g, function (_, a, b, c) { return '.' + (a || b || c); })
      .split('.')
      .filter(Boolean);
  }

  function getPath(target, path) {
    return pathParts(path).reduce(function (value, key) {
      return value == null ? undefined : value[key];
    }, target);
  }

  function setPath(target, path, value) {
    var parts = pathParts(path);
    if (!parts.length) return target;
    var cursor = target;
    parts.forEach(function (key, index) {
      if (index === parts.length - 1) {
        cursor[key] = value;
        return;
      }
      if (!cursor[key] || typeof cursor[key] !== 'object') cursor[key] = {};
      cursor = cursor[key];
    });
    return target;
  }

  function deletePath(target, path) {
    var parts = pathParts(path);
    if (!parts.length) return false;
    var key = parts.pop();
    var parent = parts.reduce(function (value, part) {
      return value && typeof value === 'object' ? value[part] : undefined;
    }, target);
    return !!parent && delete parent[key];
  }

  function mergeInto(target, source, onlyMissing) {
    if (!source || typeof source !== 'object') return target;
    Object.keys(source).forEach(function (key) {
      var incoming = source[key];
      var current = target[key];
      if (incoming && typeof incoming === 'object' && !Array.isArray(incoming)) {
        if (!current || typeof current !== 'object' || Array.isArray(current)) target[key] = {};
        mergeInto(target[key], incoming, onlyMissing);
      } else if (!onlyMissing || current === undefined) {
        target[key] = clone(incoming);
      }
    });
    return target;
  }

  function context() {
    return parseJson(FawnBridge.getFrontendContext(), {});
  }

  var variableCache = {};
  function getVariables(options) {
    var scope = variableScope(options);
    if (!scope) return {};
    if (scope === 'message') {
      var opts = normalizeOptions(options);
      var id = opts.message_id == null || opts.message_id === 'latest' ? -1 : Number(opts.message_id);
      var message = window.getChatMessages(id)[0];
      return clone(message && message.data ? message.data : {});
    }
    if (scope !== 'chat' && scope !== 'global') {
      if (!Object.prototype.hasOwnProperty.call(variableCache, scope)) {
        hostCall('variables.get', Object.assign({ scope: scope }, normalizeOptions(options))).then(function (value) {
          variableCache[scope] = value && typeof value === 'object' ? value : {};
        }).catch(function () {});
      }
      return clone(variableCache[scope] || {});
    }
    if (!Object.prototype.hasOwnProperty.call(variableCache, scope)) {
      variableCache[scope] = parseJson(FawnBridge.getVariables(scope), {});
    }
    return clone(variableCache[scope]);
  }

  async function getVariablesAsync(options) {
    var scope = variableScope(options);
    if (!scope || scope === 'chat' || scope === 'global' || scope === 'message') return getVariables(options);
    var value = await hostCall('variables.get', Object.assign({ scope: scope }, normalizeOptions(options)));
    variableCache[scope] = value && typeof value === 'object' ? value : {};
    return clone(variableCache[scope]);
  }

  function replaceVariables(variables, options) {
    var scope = variableScope(options);
    if (!scope) return Promise.resolve();
    var next = variables && typeof variables === 'object' ? variables : {};
    if (scope !== 'chat' && scope !== 'global') {
      var opts = normalizeOptions(options);
      variableCache[scope] = clone(next);
      return hostCall('variables.replace', {
        scope: scope,
        message_id: opts.message_id == null || opts.message_id === 'latest' ? -1 : Number(opts.message_id),
        value: next,
      });
    }
    variableCache[scope] = clone(next);
    FawnBridge.replaceVariables(scope, JSON.stringify(next));
    return Promise.resolve();
  }

  async function updateVariablesWith(updater, options) {
    var variables = getVariables(options);
    var updated = typeof updater === 'function' ? await updater(variables) : variables;
    await replaceVariables(updated === undefined ? variables : updated, options);
    return updated === undefined ? variables : updated;
  }

  async function insertOrAssignVariables(values, options) {
    var variables = getVariables(options);
    mergeInto(variables, values, false);
    await replaceVariables(variables, options);
    return variables;
  }

  async function insertVariables(values, options) {
    var variables = getVariables(options);
    mergeInto(variables, values, true);
    await replaceVariables(variables, options);
    return variables;
  }

  async function deleteVariable(path, options) {
    var variables = getVariables(options);
    deletePath(variables, path);
    await replaceVariables(variables, options);
    return variables;
  }

  var listeners = new Map();
  function eventOn(type, listener) {
    if (typeof listener !== 'function') return { stop: function () {} };
    var values = listeners.get(type) || [];
    if (values.indexOf(listener) < 0) values.push(listener);
    listeners.set(type, values);
    return { stop: function () { eventRemoveListener(type, listener); } };
  }
  function eventOnce(type, listener) {
    var wrapper = function () {
      eventRemoveListener(type, wrapper);
      return listener.apply(null, arguments);
    };
    return eventOn(type, wrapper);
  }
  function eventMakeFirst(type, listener) {
    eventRemoveListener(type, listener);
    var values = listeners.get(type) || [];
    values.unshift(listener);
    listeners.set(type, values);
    return { stop: function () { eventRemoveListener(type, listener); } };
  }
  function eventMakeLast(type, listener) {
    eventRemoveListener(type, listener);
    return eventOn(type, listener);
  }
  async function eventEmit(type) {
    var args = Array.prototype.slice.call(arguments, 1);
    var values = (listeners.get(type) || []).slice();
    for (var i = 0; i < values.length; i++) await values[i].apply(null, args);
  }
  function eventRemoveListener(type, listener) {
    var values = listeners.get(type) || [];
    listeners.set(type, values.filter(function (value) { return value !== listener; }));
  }
  function eventClearEvent(type) { listeners.delete(type); }
  function eventClearListener(listener) {
    listeners.forEach(function (values, type) {
      listeners.set(type, values.filter(function (value) { return value !== listener; }));
    });
  }
  function eventClearAll() { listeners.clear(); }

  function getCurrentMessageId() { return Number(context().messageId ?? -1); }
  function getLastMessageId() { return Number(context().lastMessageId ?? -1); }
  function getMessageId() { return getCurrentMessageId(); }
  function getCurrentCharacterId() { return String(context().characterId || context().characterFile || ''); }
  function getCurrentCharacterName() { return String(context().characterName || ''); }
  function getCharacter(id) {
    var ctx = context();
    if (id == null || id === 'current' || id === ctx.characterId || id === ctx.characterFile) return clone(ctx.character || null);
    return null;
  }
  function getCharData(id) { return getCharacter(id); }
  function getCurrentPersonaName() { return String(context().userName || ''); }
  function getCharAvatarPath() { return String(context().charAvatarPath || ''); }
  function getUserAvatarPath() { return String(context().userAvatarPath || ''); }
  function getCurrentChatId() { return String(context().chatId || ''); }
  async function getChatMessagesPage(options) {
    var result = await hostCall('chat.get-messages', options && typeof options === 'object' ? options : {});
    return result && typeof result === 'object' && Array.isArray(result.messages)
      ? result
      : { messages: [], offset: 0, total: 0 };
  }
  async function setChatMessages(messages) {
    return hostCall('chat.set-messages', { messages: Array.isArray(messages) ? messages : [] });
  }
  async function createChatMessages(messages, options) {
    return hostCall('chat.create-messages', Object.assign({ messages: Array.isArray(messages) ? messages : [] }, options || {}));
  }
  async function deleteChatMessages(messageIds) {
    return hostCall('chat.delete-messages', { message_ids: Array.isArray(messageIds) ? messageIds : [] });
  }
  async function rotateChatMessages(begin, middle, end) {
    return hostCall('chat.rotate-messages', { begin: begin, middle: middle, end: end });
  }
  async function getModelList() { return hostCall('generation.list-models', {}); }
  async function generate(config) {
    var result = await hostCall('generation.call', config || {});
    return result && typeof result === 'object' ? result.content : result;
  }
  async function generateRaw(config) { return hostCall('generation.call', config || {}); }
  async function stopGenerationById(id) { return hostCall('generation.stop', { generation_id: id }); }
  async function stopAllGeneration() { return hostCall('generation.stop', {}); }
  async function emitHostEvent(type, payload) { return hostCall('event.emit', { type: type, payload: payload || {} }); }
  async function triggerSlash(command) {
    var result = await hostCall('slash.run', { command: String(command || '') });
    if (result && typeof result === 'object' && result.set_input !== undefined) window.setInputText(result.set_input);
    return result;
  }

  function literalValue(value) {
    var source = String(value == null ? '' : value).trim();
    if (!source) return '';
    try { return JSON.parse(source); } catch (_) {}
    if ((source[0] === '"' && source[source.length - 1] === '"') || (source[0] === "'" && source[source.length - 1] === "'")) {
      return source.slice(1, -1).replace(/\\(['"\\])/g, '$1');
    }
    if (/^-?\d+(\.\d+)?$/.test(source)) return Number(source);
    if (source === 'true') return true;
    if (source === 'false') return false;
    if (source === 'null') return null;
    return source;
  }

  function mvuData(options) { return getVariables(options); }
  async function replaceMvuData(value, options) {
    var previous = mvuData(options);
    await eventEmit(Mvu.events.VARIABLE_UPDATE_STARTED, clone(previous));
    await replaceVariables(value || {}, options);
    await eventEmit(Mvu.events.VARIABLE_UPDATE_ENDED, clone(value || {}), previous);
  }
  async function parseMvuMessage(message, oldData) {
    var next = clone(oldData || {});
    var commands = [];
    var source = String(message == null ? '' : message);
    var matcher = /_\.(set|add|unset|delete)\(\s*(['"])(.*?)\2(?:\s*,\s*([^\n;)]*))?\s*\)/g;
    var match;
    while ((match = matcher.exec(source))) {
      var operation = match[1] === 'unset' ? 'delete' : match[1];
      var path = match[3];
      var value = literalValue(match[4]);
      commands.push({ type: operation, full_match: match[0], args: match[4] === undefined ? [path] : [path, value], reason: '' });
      if (operation === 'set') setPath(next, path, value);
      else if (operation === 'add') {
        var current = Number(getPath(next, path) || 0);
        setPath(next, path, current + Number(value || 0));
      } else deletePath(next, path);
    }
    await eventEmit(Mvu.events.COMMAND_PARSED, next, commands, source);
    return next;
  }

  var Mvu = {
    events: Object.freeze({
      VARIABLE_INITIALIZED: 'mag_variable_initiailized',
      VARIABLE_UPDATE_STARTED: 'mag_variable_update_started',
      COMMAND_PARSED: 'mag_command_parsed',
      VARIABLE_UPDATE_ENDED: 'mag_variable_update_ended',
      BEFORE_MESSAGE_UPDATE: 'mag_before_message_update',
    }),
    getMvuData: mvuData,
    replaceMvuData: replaceMvuData,
    parseMessage: parseMvuMessage,
    isDuringExtraAnalysis: function () { return false; },
  };

  var ejsFeatures = {};
  function allTemplateVariables(endMessageId) {
    var merged = {};
    mergeInto(merged, getVariables({ type: 'global' }), false);
    mergeInto(merged, getVariables({ type: 'chat' }), false);
    var end = endMessageId == null ? getLastMessageId() : Number(endMessageId);
    for (var i = 0; i <= end; i++) {
      var message = window.getChatMessages(i)[0];
      if (message && message.data) mergeInto(merged, message.data, false);
    }
    return merged;
  }

  function templateOptions(options) {
    if (typeof options === 'string') return { scope: options };
    return options && typeof options === 'object' ? options : {};
  }

  function templateScope(options, fallback) {
    var raw = String(options.scope || options.outscope || options.type || fallback || 'cache').toLowerCase();
    if (raw === 'local' || raw === 'chat') return 'chat';
    if (raw === 'global' || raw === 'message') return raw;
    return 'cache';
  }

  function templateMessageOptions(options, scope) {
    if (scope !== 'message' || options.message_id != null) return options;
    var messageId = context().messageId;
    return messageId == null ? options : Object.assign({}, options, { message_id: messageId });
  }

  function templateEndMessageId(value) {
    if (value != null) return value;
    var messageId = context().messageId;
    return messageId == null ? undefined : Number(messageId);
  }

  function templateVariableStore(options) {
    var opts = templateOptions(options);
    var scope = templateScope(opts);
    if (scope === 'cache') return allTemplateVariables(templateEndMessageId(opts.message_id));
    return getVariables(Object.assign({}, templateMessageOptions(opts, scope), { type: scope }));
  }

  function templateGetVariable(key, options) {
    var opts = templateOptions(options);
    var value = key == null ? templateVariableStore(opts) : getPath(templateVariableStore(opts), key);
    if (opts.index != null && value != null) {
      var index = Number(opts.index);
      value = value[Number.isNaN(index) ? opts.index : index];
    }
    if (value === undefined) value = opts.defaults;
    return opts.clone ? clone(value) : value;
  }

  function persistTemplateVariables(value, options) {
    var opts = templateOptions(options);
    var scope = templateScope(opts, 'chat');
    if (scope === 'cache') scope = 'chat';
    replaceVariables(value, Object.assign({}, templateMessageOptions(opts, scope), { type: scope })).catch(function (error) {
      console.warn('[FawnTavern] failed to persist prompt-template variables', error);
    });
  }

  function templateSetVariable(key, value, options) {
    var opts = templateOptions(options);
    var scope = templateScope(opts, 'chat');
    if (scope === 'cache') scope = 'chat';
    var current = templateVariableStore(Object.assign({}, opts, { scope: scope }));
    var previous = key == null ? clone(current) : getPath(current, key);
    if (key == null) {
      if (value && typeof value === 'object' && !Array.isArray(value)) {
        Object.keys(current).forEach(function (name) { delete current[name]; });
        mergeInto(current, value, false);
      }
    } else if (value === undefined) {
      deletePath(current, key);
    } else {
      setPath(current, key, value);
    }
    persistTemplateVariables(current, Object.assign({}, opts, { scope: scope }));
    return opts.results === 'old' ? previous : opts.results === 'fullcache' ? allTemplateVariables() : value;
  }

  function templateIncreaseVariable(key, amount, options) {
    var current = Number(templateGetVariable(key, Object.assign({}, templateOptions(options), {
      scope: templateOptions(options).inscope || templateOptions(options).scope,
      defaults: 0,
    })) || 0);
    var next = current + Number(amount == null ? 1 : amount);
    var opts = templateOptions(options);
    if (opts.min != null) next = Math.max(next, Number(opts.min));
    if (opts.max != null) next = Math.min(next, Number(opts.max));
    return templateSetVariable(key, next, Object.assign({}, opts, { scope: opts.outscope || opts.scope }));
  }

  function templateDeleteVariable(key, options) {
    return templateSetVariable(key, undefined, options);
  }

  function templateInsertVariable(key, value, index, options) {
    var current = templateGetVariable(key, options);
    if (Array.isArray(current)) {
      var copy = current.slice();
      var position = index == null ? copy.length : Number(index);
      if (position < 0) position = copy.length + position;
      copy.splice(Math.max(0, position), 0, value);
      return templateSetVariable(key, copy, options);
    }
    if (current && typeof current === 'object' && index != null) {
      var objectCopy = clone(current);
      objectCopy[String(index)] = value;
      return templateSetVariable(key, objectCopy, options);
    }
    if (typeof current === 'string') {
      var stringPosition = index == null ? current.length : Number(index);
      if (stringPosition < 0) stringPosition = current.length + stringPosition;
      return templateSetVariable(
        key,
        current.slice(0, Math.max(0, stringPosition)) + String(value) + current.slice(Math.max(0, stringPosition)),
        options,
      );
    }
    return undefined;
  }

  async function prepareTemplateContext(additionalContext, endMessageId) {
    var effectiveEndMessageId = templateEndMessageId(endMessageId);
    var ctx = Object.assign({}, getContext(), allTemplateVariables(effectiveEndMessageId), additionalContext || {});
    Object.defineProperty(ctx, 'variables', {
      enumerable: true,
      get: function () { return allTemplateVariables(effectiveEndMessageId); },
    });
    return Object.assign(ctx, {
      getvar: templateGetVariable,
      getLocalVar: function (key, options) { return templateGetVariable(key, Object.assign({}, templateOptions(options), { scope: 'local' })); },
      getGlobalVar: function (key, options) { return templateGetVariable(key, Object.assign({}, templateOptions(options), { scope: 'global' })); },
      getMessageVar: function (key, options) { return templateGetVariable(key, Object.assign({}, templateOptions(options), { scope: 'message' })); },
      setvar: templateSetVariable,
      setLocalVar: function (key, value, options) { return templateSetVariable(key, value, Object.assign({}, templateOptions(options), { scope: 'local' })); },
      setGlobalVar: function (key, value, options) { return templateSetVariable(key, value, Object.assign({}, templateOptions(options), { scope: 'global' })); },
      setMessageVar: function (key, value, options) { return templateSetVariable(key, value, Object.assign({}, templateOptions(options), { scope: 'message' })); },
      incvar: templateIncreaseVariable,
      incLocalVar: function (key, value, options) { return templateIncreaseVariable(key, value, Object.assign({}, templateOptions(options), { outscope: 'local' })); },
      incGlobalVar: function (key, value, options) { return templateIncreaseVariable(key, value, Object.assign({}, templateOptions(options), { outscope: 'global' })); },
      incMessageVar: function (key, value, options) { return templateIncreaseVariable(key, value, Object.assign({}, templateOptions(options), { outscope: 'message' })); },
      decvar: function (key, value, options) { return templateIncreaseVariable(key, -Number(value == null ? 1 : value), options); },
      decLocalVar: function (key, value, options) { return templateIncreaseVariable(key, -Number(value == null ? 1 : value), Object.assign({}, templateOptions(options), { outscope: 'local' })); },
      decGlobalVar: function (key, value, options) { return templateIncreaseVariable(key, -Number(value == null ? 1 : value), Object.assign({}, templateOptions(options), { outscope: 'global' })); },
      decMessageVar: function (key, value, options) { return templateIncreaseVariable(key, -Number(value == null ? 1 : value), Object.assign({}, templateOptions(options), { outscope: 'message' })); },
      delvar: templateDeleteVariable,
      delLocalVar: function (key, options) { return templateDeleteVariable(key, Object.assign({}, templateOptions(options), { scope: 'local' })); },
      delGlobalVar: function (key, options) { return templateDeleteVariable(key, Object.assign({}, templateOptions(options), { scope: 'global' })); },
      delMessageVar: function (key, options) { return templateDeleteVariable(key, Object.assign({}, templateOptions(options), { scope: 'message' })); },
      insvar: templateInsertVariable,
      insertLocalVar: function (key, value, index, options) { return templateInsertVariable(key, value, index, Object.assign({}, templateOptions(options), { scope: 'local' })); },
      insertGlobalVar: function (key, value, index, options) { return templateInsertVariable(key, value, index, Object.assign({}, templateOptions(options), { scope: 'global' })); },
      insertMessageVar: function (key, value, index, options) { return templateInsertVariable(key, value, index, Object.assign({}, templateOptions(options), { scope: 'message' })); },
    });
  }
  async function evalTemplate(template, templateContext) {
    var ctx = templateContext || await prepareTemplateContext();
    var source = String(template == null ? '' : template)
      .replace(/&lt;%(?=[_=-]?)/gi, '<%')
      .replace(/%&gt;/gi, '%>');
    var matcher = /<%([=_-]?)([\s\S]*?)%>/g;
    var cursor = 0;
    var body = "let __out='';\n";
    var match;
    while ((match = matcher.exec(source))) {
      body += '__out+=' + JSON.stringify(source.slice(cursor, match.index)) + ';\n';
      var kind = match[1];
      var code = match[2];
      if (kind === '=' || kind === '-') body += '__out+=String(await (' + code + '));\n';
      else if (kind === '_') body += code.replace(/_\s*$/, '') + '\n';
      else body += code + '\n';
      cursor = matcher.lastIndex;
    }
    body += '__out+=' + JSON.stringify(source.slice(cursor)) + ';\nreturn __out;';
    var AsyncFunction = Object.getPrototypeOf(async function () {}).constructor;
    return new AsyncFunction('ctx', 'with(ctx){' + body + '}')(ctx);
  }
  var EjsTemplate = {
    evalTemplate: evalTemplate,
    evaltemplate: evalTemplate,
    prepareContext: prepareTemplateContext,
    allVariables: allTemplateVariables,
    getFeatures: function () { return clone(ejsFeatures); },
    setFeatures: function (features) { mergeInto(ejsFeatures, features || {}, false); },
    resetFeatures: function () { ejsFeatures = {}; },
    getSyntaxErrorInfo: async function (template) {
      try { await evalTemplate(template, {}); return ''; } catch (error) { return String(error && error.message || error); }
    },
  };
  function substitudeMacros(text) {
    var value = String(text == null ? '' : text);
    var ctx = context();
    return value
      .replace(/\{\{user\}\}/gi, String(ctx.userName || ''))
      .replace(/\{\{char\}\}/gi, String(ctx.characterName || ''))
      .replace(/\{\{lastMessageId\}\}/gi, String(ctx.lastMessageId ?? -1))
      .replace(/\{\{userAvatarPath\}\}/gi, String(ctx.userAvatarPath || ''))
      .replace(/\{\{charAvatarPath\}\}/gi, String(ctx.charAvatarPath || ''))
      .replace(/\{\{newline\}\}/gi, '\n');
  }

  var globals = new Map();
  function initializeGlobal(name, value) { globals.set(name, value); window[name] = value; }
  async function waitGlobalInitialized(name) {
    if (window[name] !== undefined) return window[name];
    return globals.get(name);
  }

  function getContext() {
    var ctx = context();
    return Object.assign({}, ctx, {
      chat: window.getChatMessages(null, { include_swipes: true }),
      name1: ctx.userName || '',
      name2: ctx.characterName || '',
      characterId: ctx.characterId || ctx.characterFile || '',
      getCurrentChatId: function () { return ctx.chatId || ''; },
    });
  }

  var tavernEvents = Object.freeze({
    APP_READY: 'app_ready',
    CHAT_CHANGED: 'chat_id_changed',
    MESSAGE_SENT: 'message_sent',
    MESSAGE_RECEIVED: 'message_received',
    MESSAGE_EDITED: 'message_edited',
    MESSAGE_DELETED: 'message_deleted',
    MESSAGE_SWIPED: 'message_swiped',
    GENERATION_STARTED: 'generation_started',
    STREAM_TOKEN_RECEIVED: 'stream_token_received',
    GENERATION_ENDED: 'generation_ended',
    VARIABLE_UPDATED: 'variable_updated',
  });

  var api = {
    getChatMessages: window.getChatMessages,
    getChatMessagesPage: getChatMessagesPage,
    setChatMessage: window.setChatMessage,
    setChatMessages: setChatMessages,
    createChatMessages: createChatMessages,
    deleteChatMessages: deleteChatMessages,
    rotateChatMessages: rotateChatMessages,
    getModelList: getModelList,
    generate: generate,
    generateRaw: generateRaw,
    stopGenerationById: stopGenerationById,
    stopAllGeneration: stopAllGeneration,
    emitHostEvent: emitHostEvent,
    triggerSlash: triggerSlash,
    triggerSlashWithResult: triggerSlash,
    Mvu: Mvu,
    EjsTemplate: EjsTemplate,
    setInputText: window.setInputText,
    getVariables: getVariables,
    getVariablesAsync: getVariablesAsync,
    replaceVariables: replaceVariables,
    updateVariablesWith: updateVariablesWith,
    insertOrAssignVariables: insertOrAssignVariables,
    insertVariables: insertVariables,
    deleteVariable: deleteVariable,
    getCurrentMessageId: getCurrentMessageId,
    getLastMessageId: getLastMessageId,
    getMessageId: getMessageId,
    getCurrentCharacterId: getCurrentCharacterId,
    getCurrentCharacterName: getCurrentCharacterName,
    getCharacter: getCharacter,
    getCharData: getCharData,
    getCurrentPersonaName: getCurrentPersonaName,
    getCharAvatarPath: getCharAvatarPath,
    getUserAvatarPath: getUserAvatarPath,
    getCurrentChatId: getCurrentChatId,
    substitudeMacros: substitudeMacros,
    initializeGlobal: initializeGlobal,
    waitGlobalInitialized: waitGlobalInitialized,
    eventOn: eventOn,
    eventOnButton: eventOn,
    eventOnce: eventOnce,
    eventMakeFirst: eventMakeFirst,
    eventMakeLast: eventMakeLast,
    eventEmit: eventEmit,
    eventEmitAndWait: eventEmit,
    eventRemoveListener: eventRemoveListener,
    eventClearEvent: eventClearEvent,
    eventClearListener: eventClearListener,
    eventClearAll: eventClearAll,
    tavern_events: tavernEvents,
    hostCall: hostCall,
  };

  Object.keys(api).forEach(function (key) {
    if (window[key] === undefined) window[key] = api[key];
  });
  window.TavernHelper = Object.assign({}, window.TavernHelper || {}, api);
  window.tavern_events = window.tavern_events || tavernEvents;
  window.Mvu = window.Mvu || Mvu;
  window.EjsTemplate = window.EjsTemplate || EjsTemplate;
  window.iframe_events = window.iframe_events || Object.freeze({
    MESSAGE_IFRAME_RENDER_STARTED: 'message_iframe_render_started',
    MESSAGE_IFRAME_RENDER_ENDED: 'message_iframe_render_ended',
  });
  window.SillyTavern = Object.assign({}, window.SillyTavern || {}, { getContext: getContext });
  if (!window.YAML && window.jsyaml) {
    window.YAML = Object.assign({}, window.jsyaml, {
      parse: window.jsyaml.load,
      stringify: window.jsyaml.dump,
    });
  }
  window.z = window.z || window.Zod;

  var avatarStyle = document.createElement('style');
  avatarStyle.setAttribute('data-fawn-runtime', 'avatars');
  document.head.appendChild(avatarStyle);
  function refreshContext() {
    variableCache = {};
    var ctx = context();
    avatarStyle.textContent =
      '.user_avatar,.user-avatar{background-image:url("' + String(ctx.userAvatarPath || '').replace(/"/g, '%22') + '")}' +
      '.char_avatar,.char-avatar{background-image:url("' + String(ctx.charAvatarPath || '').replace(/"/g, '%22') + '")}';
  }
  window.__fawnCompatibilityContextChanged = refreshContext;
  refreshContext();

  window.addEventListener('pagehide', function () {
    eventClearAll();
    pendingRpc.forEach(function (pending, requestId) {
      try { FawnBridge.cancel(requestId); } catch (_) {}
      pending.reject(new Error('frontend-runtime-closed'));
    });
    pendingRpc.clear();
  }, { once: true });
  setTimeout(function () { eventEmit(tavernEvents.APP_READY); }, 0);
})();
