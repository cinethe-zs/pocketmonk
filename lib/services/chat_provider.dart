import 'dart:async';

import 'package:flutter/foundation.dart';

import '../models/conversation.dart';
import '../models/message.dart';
import '../services/conversation_store.dart';
import '../services/llm_service.dart';

class ChatProvider extends ChangeNotifier {
  LlmService _llm;

  Conversation?      _current;
  List<Conversation> _allConversations = [];

  bool    _isGenerating = false;
  String? _errorMessage;
  String  _streamBuffer = '';
  StreamSubscription<String>? _streamSub;

  ChatProvider(this._llm);

  // ── Getters ──────────────────────────────────────────────────────────────
  List<Message>      get messages            => List.unmodifiable(_current?.messages ?? []);
  bool               get isGenerating        => _isGenerating;
  bool               get isEmpty             => (_current?.messages ?? []).isEmpty;
  String?            get errorMessage        => _errorMessage;
  LlmService         get llm                 => _llm;
  Conversation?      get currentConversation => _current;
  List<Conversation> get allConversations    => List.unmodifiable(_allConversations);

  // ── Bootstrap ─────────────────────────────────────────────────────────────

  Future<void> init() async {
    _allConversations = await ConversationStore.loadAll();
    if (_allConversations.isNotEmpty) {
      _current = _allConversations.first;
    }
    notifyListeners();
  }

  // ── Conversation management ───────────────────────────────────────────────

  void newConversation(String modelPath) {
    stopGeneration();
    _current = Conversation(title: 'New conversation', modelPath: modelPath);
    _errorMessage = null;
    notifyListeners();
  }

  Future<void> loadConversation(Conversation conv) async {
    stopGeneration();
    _current      = conv;
    _errorMessage = null;
    notifyListeners();
  }

  Future<void> deleteConversation(String id) async {
    await ConversationStore.delete(id);
    _allConversations.removeWhere((c) => c.id == id);
    if (_current?.id == id) {
      _current = _allConversations.isNotEmpty ? _allConversations.first : null;
    }
    notifyListeners();
  }

  Future<void> _persistCurrent() async {
    if (_current == null) return;
    await ConversationStore.save(_current!);
    _allConversations = await ConversationStore.loadAll();
    notifyListeners();
  }

  // ── System prompt ─────────────────────────────────────────────────────────

  void setSystemPrompt(String? prompt) {
    if (_current == null) return;
    final trimmed = prompt?.trim();
    _current!.systemPrompt = (trimmed == null || trimmed.isEmpty) ? null : trimmed;
    _persistCurrent();
    notifyListeners();
  }

  // ── Tags ──────────────────────────────────────────────────────────────────

  void addTag(String tag) {
    if (_current == null || tag.trim().isEmpty) return;
    final t = tag.trim();
    if (!_current!.tags.contains(t)) {
      _current!.tags.add(t);
      _persistCurrent();
      notifyListeners();
    }
  }

  void removeTag(String tag) {
    if (_current == null) return;
    _current!.tags.remove(tag);
    _persistCurrent();
    notifyListeners();
  }

  void addTagToConversation(Conversation conv, String tag) {
    final t = tag.trim();
    if (t.isEmpty || conv.tags.contains(t)) return;
    conv.tags.add(t);
    ConversationStore.save(conv);
    notifyListeners();
  }

  void removeTagFromConversation(Conversation conv, String tag) {
    conv.tags.remove(tag);
    ConversationStore.save(conv);
    notifyListeners();
  }

  // ── Star messages ─────────────────────────────────────────────────────────

  void toggleStarMessage(String messageId) {
    if (_current == null) return;
    final idx = _current!.messages.indexWhere((m) => m.id == messageId);
    if (idx == -1) return;
    final msg = _current!.messages[idx];
    _current!.messages[idx] = msg.copyWith(starred: !msg.starred);
    _persistCurrent();
    notifyListeners();
  }

  // ── Inference ─────────────────────────────────────────────────────────────

  Future<void> sendMessage(String text) async {
    final trimmed = text.trim();
    if (trimmed.isEmpty || _isGenerating) return;

    _errorMessage = null;

    if (_current == null) {
      _current = Conversation(
        title:     _titleFrom(trimmed),
        modelPath: _llm.config?.modelPath ?? '',
      );
    }

    if (_current!.messages.isEmpty) {
      _current!.title = _titleFrom(trimmed);
    }

    final userMsg = Message(role: MessageRole.user, content: trimmed);
    _current!.messages.add(userMsg);
    notifyListeners();
    _persistCurrent(); // persist user message immediately — crash-safe

    final assistantMsg = Message(
      role:    MessageRole.assistant,
      content: '',
      status:  MessageStatus.streaming,
    );
    _current!.messages.add(assistantMsg);
    _isGenerating = true;
    _streamBuffer = '';
    notifyListeners();

    final history = _current!.messages
        .where((m) => m.isUser || (m.isAssistant && m.content.isNotEmpty))
        .map((m) => {'role': m.isUser ? 'user' : 'assistant', 'content': m.content})
        .toList();

    try {
      _streamSub = _llm.chat(
        history,
        systemPromptOverride: _current?.systemPrompt,
      ).listen(
        (fullText) {
          _streamBuffer = fullText;
          _updateLastAssistantMessage(_streamBuffer, MessageStatus.streaming);
        },
        onDone: () {
          _updateLastAssistantMessage(_streamBuffer, MessageStatus.done);
          _finishGeneration();
          _persistCurrent();
        },
        onError: (Object e) {
          _errorMessage = e.toString();
          _updateLastAssistantMessage(e.toString(), MessageStatus.error);
          _finishGeneration();
        },
        cancelOnError: true,
      );
    } catch (e) {
      _errorMessage = e.toString();
      _updateLastAssistantMessage(e.toString(), MessageStatus.error);
      _finishGeneration();
    }
  }

  void stopGeneration() {
    _streamSub?.cancel();
    _streamSub = null;
    _llm.cancel();
    if (_current != null &&
        _current!.messages.isNotEmpty &&
        _current!.messages.last.isStreaming) {
      _updateLastAssistantMessage(_streamBuffer, MessageStatus.done);
      _persistCurrent();
    }
    _finishGeneration();
  }

  // ── Regenerate ────────────────────────────────────────────────────────────

  Future<void> regenerateLastResponse() async {
    if (_isGenerating || _current == null || _current!.messages.isEmpty) return;
    if (!_current!.messages.last.isAssistant) return;

    // Remove last assistant response
    _current!.messages.removeLast();

    // Get and remove the last user message (sendMessage will re-add it)
    if (_current!.messages.isEmpty || !_current!.messages.last.isUser) {
      notifyListeners();
      return;
    }
    final userText = _current!.messages.last.content;
    _current!.messages.removeLast();
    notifyListeners();

    await sendMessage(userText);
  }

  // ── Edit message ──────────────────────────────────────────────────────────

  Future<void> editMessageAt(int index, String newContent) async {
    if (_isGenerating || _current == null) return;
    final trimmed = newContent.trim();
    if (trimmed.isEmpty) return;

    // Truncate from this index onwards and re-run
    _current!.messages.removeRange(index, _current!.messages.length);
    notifyListeners();
    await sendMessage(trimmed);
  }

  // ── Clear ─────────────────────────────────────────────────────────────────

  void clearHistory() {
    stopGeneration();
    if (_current != null) {
      _current!.messages.clear();
      _current!.title = 'New conversation';
      _persistCurrent();
    }
    _errorMessage = null;
    notifyListeners();
  }

  // ── Model switching ───────────────────────────────────────────────────────

  Future<void> switchModel(LlmService newService) async {
    stopGeneration();
    _llm = newService;
    if (_current != null) {
      _current!.modelPath = newService.config?.modelPath ?? '';
    }
    notifyListeners();
  }

  // ── Private helpers ───────────────────────────────────────────────────────

  void _updateLastAssistantMessage(String content, MessageStatus status) {
    if (_current == null) return;
    final idx = _current!.messages.lastIndexWhere((m) => m.isAssistant);
    if (idx == -1) return;
    _current!.messages[idx] =
        _current!.messages[idx].copyWith(content: content, status: status);
    notifyListeners();
  }

  void _finishGeneration() {
    _isGenerating = false;
    _streamSub    = null;
    notifyListeners();
  }

  String _titleFrom(String text) {
    final s = text.trim().replaceAll('\n', ' ');
    return s.length > 40 ? '${s.substring(0, 40)}…' : s;
  }

  @override
  void dispose() {
    _streamSub?.cancel();
    _llm.dispose();
    super.dispose();
  }
}
