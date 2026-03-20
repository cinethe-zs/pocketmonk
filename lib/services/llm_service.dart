import 'dart:async';
import 'dart:io';

import 'package:flutter/services.dart' show rootBundle;
import 'package:fllama/fllama.dart';
import 'package:path_provider/path_provider.dart';

/// Holds the current inference configuration.
class LlmConfig {
  final String modelPath;
  final int contextLength;
  final double temperature;
  final double topP;
  final int maxTokens;

  const LlmConfig({
    required this.modelPath,
    this.contextLength = 4096,
    this.temperature   = 0.7,
    this.topP          = 0.9,
    this.maxTokens     = 2048,
  });

  LlmConfig copyWith({
    String? modelPath,
    int?    contextLength,
    double? temperature,
    double? topP,
    int?    maxTokens,
  }) {
    return LlmConfig(
      modelPath:     modelPath     ?? this.modelPath,
      contextLength: contextLength ?? this.contextLength,
      temperature:   temperature   ?? this.temperature,
      topP:          topP          ?? this.topP,
      maxTokens:     maxTokens     ?? this.maxTokens,
    );
  }
}

/// Status of the LLM service.
enum LlmServiceStatus { unloaded, loading, ready, inferring, error }

class LlmService {
  LlmConfig?       _config;
  LlmServiceStatus _status = LlmServiceStatus.unloaded;
  String?          _errorMessage;
  String           _systemPrompt = '';
  bool             _cancelled    = false;

  // ── Getters ──────────────────────────────────────────────────────────────
  LlmServiceStatus get status       => _status;
  String?          get errorMessage => _errorMessage;
  LlmConfig?       get config       => _config;
  bool             get isReady      => _status == LlmServiceStatus.ready;
  bool             get isInferring  => _status == LlmServiceStatus.inferring;

  // ── Initialisation ───────────────────────────────────────────────────────

  Future<void> initialize(LlmConfig config) async {
    _config       = config;
    _systemPrompt = await rootBundle.loadString('assets/system_prompt.txt');
    _status       = LlmServiceStatus.ready;
  }

  static Future<String> modelsDirectory() async {
    final dir  = await getExternalStorageDirectory();
    final base = dir ?? await getApplicationDocumentsDirectory();
    final models = Directory('${base.path}/models');
    if (!await models.exists()) await models.create(recursive: true);
    return models.path;
  }

  static Future<String> pathForModel(String filename) async {
    final dir = await modelsDirectory();
    return '$dir/$filename';
  }

  Future<bool> modelExists() async {
    if (_config == null) return false;
    return File(_config!.modelPath).exists();
  }

  // ── Inference ─────────────────────────────────────────────────────────────

  Stream<String> chat(List<Map<String, String>> history) {
    if (_config == null) {
      return Stream.error('LLM not initialised. Call initialize() first.');
    }

    final controller = StreamController<String>();
    _cancelled = false;
    _status    = LlmServiceStatus.inferring;

    final messages = <Message>[
      Message(Role.system, _systemPrompt),
      ...history.map((m) {
        final role = m['role'] == 'user' ? Role.user : Role.assistant;
        return Message(role, m['content']!);
      }),
    ];

    _infer(messages, controller);
    return controller.stream;
  }

  Future<void> _infer(
    List<Message>            messages,
    StreamController<String> out,
  ) async {
    if (_cancelled || out.isClosed) return;

    final modelFile = File(_config!.modelPath);
    if (!await modelFile.exists()) {
      out.addError('Model file not found at:\n${_config!.modelPath}');
      out.close();
      return;
    }

    final request = OpenAiRequest(
      maxTokens:        _config!.maxTokens,
      messages:         messages,
      tools:            [],       // no tools — plain chat only
      modelPath:        _config!.modelPath,
      numGpuLayers:     0,
      contextSize:      _config!.contextLength,
      temperature:      _config!.temperature,
      topP:             _config!.topP,
      frequencyPenalty: 0.0,
      presencePenalty:  1.1,
    );

    final completer = Completer<void>();

    fllamaChat(request, (response, jsonStr, done) {
      if (_cancelled) {
        if (!completer.isCompleted) completer.complete();
        return;
      }
      if (!out.isClosed) out.add(response);
      if (done && !completer.isCompleted) completer.complete();
    });

    await completer.future;

    _status = LlmServiceStatus.ready;
    if (!out.isClosed) out.close();
  }

  // ── Lifecycle ──────────────────────────────────────────────────────────────

  void cancel() {
    _cancelled = true;
    _status    = LlmServiceStatus.ready;
  }

  void dispose() => cancel();
}
