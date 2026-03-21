import 'dart:async';
import 'dart:io';

import 'package:flutter/services.dart' show rootBundle;
import 'package:fllama/fllama.dart';
import 'package:path_provider/path_provider.dart';

/// Holds the current inference configuration.
class LlmConfig {
  final String modelPath;
  final int    contextLength;
  final double temperature;
  final double topP;
  final int    maxTokens;
  final int    numGpuLayers; // 0 = CPU-only; >0 = offload N layers to GPU

  const LlmConfig({
    required this.modelPath,
    this.contextLength = 4096,
    this.temperature   = 0.7,
    this.topP          = 0.9,
    this.maxTokens     = 2048,
    this.numGpuLayers  = 0,
  });

  LlmConfig copyWith({
    String? modelPath,
    int?    contextLength,
    double? temperature,
    double? topP,
    int?    maxTokens,
    int?    numGpuLayers,
  }) {
    return LlmConfig(
      modelPath:     modelPath     ?? this.modelPath,
      contextLength: contextLength ?? this.contextLength,
      temperature:   temperature   ?? this.temperature,
      topP:          topP          ?? this.topP,
      maxTokens:     maxTokens     ?? this.maxTokens,
      numGpuLayers:  numGpuLayers  ?? this.numGpuLayers,
    );
  }
}

/// Status of the LLM service.
enum LlmServiceStatus { unloaded, loading, ready, inferring, error }

class LlmService {
  LlmConfig?       _config;
  LlmServiceStatus _status = LlmServiceStatus.unloaded;
  String?          _errorMessage;
  String           _systemPrompt    = '';
  bool             _cancelled       = false;
  bool             _gpuFallback     = false; // true if last run fell back to CPU

  // ── Getters ──────────────────────────────────────────────────────────────
  LlmServiceStatus get status           => _status;
  String?          get errorMessage     => _errorMessage;
  LlmConfig?       get config           => _config;
  bool             get isReady          => _status == LlmServiceStatus.ready;
  bool             get isInferring      => _status == LlmServiceStatus.inferring;
  bool             get gpuFallback      => _gpuFallback;

  // ── Initialisation ───────────────────────────────────────────────────────

  Future<void> initialize(LlmConfig config) async {
    _config       = config;
    _systemPrompt = await rootBundle.loadString('assets/system_prompt.txt');
    _status       = LlmServiceStatus.ready;
    _gpuFallback  = false;
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

  Stream<String> chat(
    List<Map<String, String>> history, {
    String? systemPromptOverride,
    String? contextSummary,
  }) {
    if (_config == null) {
      return Stream.error('LLM not initialised. Call initialize() first.');
    }

    final controller = StreamController<String>();
    _cancelled   = false;
    _gpuFallback = false;
    _status      = LlmServiceStatus.inferring;

    var sysPrompt = systemPromptOverride ?? _systemPrompt;
    if (contextSummary != null && contextSummary.isNotEmpty) {
      sysPrompt += '\n\n[Summary of earlier conversation: $contextSummary]';
    }

    final messages = <Message>[
      Message(Role.system, sysPrompt),
      ...history.map((m) {
        final role = m['role'] == 'user' ? Role.user : Role.assistant;
        return Message(role, m['content']!);
      }),
    ];

    _infer(messages, controller);
    return controller.stream;
  }

  /// Asks the LLM to produce a compact summary of [messages].
  Future<String?> summarizeHistory(
      List<Map<String, String>> messages) async {
    if (_config == null || _status == LlmServiceStatus.inferring) return null;

    final buf = StringBuffer();
    for (final m in messages) {
      final role = m['role'] == 'user' ? 'User' : 'Assistant';
      buf.writeln('$role: ${m['content']}');
      buf.writeln();
    }

    final request = OpenAiRequest(
      maxTokens:        300,
      messages:         [
        Message(
          Role.system,
          'You are a conversation summarizer. Write a concise summary '
          '(2-4 sentences) of the conversation below. Focus on key topics, '
          'decisions, and facts. Start directly with the summary.',
        ),
        Message(Role.user, buf.toString()),
      ],
      tools:            [],
      modelPath:        _config!.modelPath,
      numGpuLayers:     0,
      contextSize:      _config!.contextLength,
      temperature:      0.3,
      topP:             0.9,
      frequencyPenalty: 0.0,
      presencePenalty:  0.0,
    );

    final completer = Completer<String?>();
    String result   = '';
    try {
      fllamaChat(request, (response, _, done) {
        result = response.trim();
        if (done && !completer.isCompleted) completer.complete(result);
      });
      return await completer.future
          .timeout(const Duration(seconds: 30), onTimeout: () => null);
    } catch (_) {
      if (!completer.isCompleted) completer.complete(null);
      return null;
    }
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

    // Try with the configured GPU layers; fall back to CPU on failure.
    final gpuLayersToTry = [_config!.numGpuLayers];
    if (_config!.numGpuLayers > 0) gpuLayersToTry.add(0);

    for (final gpuLayers in gpuLayersToTry) {
      if (_cancelled || out.isClosed) return;

      final isFallback = gpuLayers == 0 && _config!.numGpuLayers > 0;

      final request = OpenAiRequest(
        maxTokens:        _config!.maxTokens,
        messages:         messages,
        tools:            [],
        modelPath:        _config!.modelPath,
        numGpuLayers:     gpuLayers,
        contextSize:      _config!.contextLength,
        temperature:      _config!.temperature,
        topP:             _config!.topP,
        frequencyPenalty: 0.0,
        presencePenalty:  1.1,
      );

      final completer  = Completer<void>();
      bool inferError  = false;

      try {
        fllamaChat(request, (response, jsonStr, done) {
          if (_cancelled) {
            if (!completer.isCompleted) completer.complete();
            return;
          }
          // Detect a GPU-level failure in the response text
          if (done && gpuLayers > 0 && _looksLikeGpuError(response)) {
            inferError = true;
            if (!completer.isCompleted) completer.complete();
            return;
          }
          if (!out.isClosed) out.add(response);
          if (done && !completer.isCompleted) completer.complete();
        });

        await completer.future;
      } catch (e) {
        // Synchronous throw from fllama (e.g. unsupported GPU backend)
        if (gpuLayers > 0 && gpuLayersToTry.length > 1) {
          _gpuFallback = true;
          continue; // retry with CPU
        }
        if (!out.isClosed) {
          out.addError(e.toString());
          out.close();
        }
        _status = LlmServiceStatus.ready;
        return;
      }

      if (inferError && gpuLayersToTry.length > 1) {
        _gpuFallback = true;
        continue; // retry with CPU
      }

      if (isFallback) _gpuFallback = true;
      break; // success
    }

    _status = LlmServiceStatus.ready;
    if (!out.isClosed) out.close();
  }

  // Heuristic: fllama may surface GPU/Vulkan errors as response text
  bool _looksLikeGpuError(String response) {
    final lower = response.toLowerCase();
    return lower.contains('vulkan') && lower.contains('error') ||
        lower.contains('ggml_vk') ||
        lower.contains('gpu layer') && lower.contains('fail');
  }

  // ── Title generation ──────────────────────────────────────────────────────

  /// Runs a tiny inference to produce a 4-6 word title from the first exchange.
  /// Returns null if the model is busy or inference fails.
  Future<String?> generateTitle(
      String firstUserMsg, String firstAssistantMsg) async {
    if (_config == null || _status == LlmServiceStatus.inferring) return null;

    final messages = [
      Message(
        Role.system,
        'Generate a short title of 4-6 words for the conversation below. '
        'Respond with ONLY the title. No punctuation, no quotes, no explanation.',
      ),
      Message(Role.user, firstUserMsg.length > 300
          ? firstUserMsg.substring(0, 300)
          : firstUserMsg),
      Message(Role.assistant, firstAssistantMsg.length > 300
          ? firstAssistantMsg.substring(0, 300)
          : firstAssistantMsg),
      Message(Role.user, 'Title:'),
    ];

    final request = OpenAiRequest(
      maxTokens:        16,
      messages:         messages,
      tools:            [],
      modelPath:        _config!.modelPath,
      numGpuLayers:     0,
      contextSize:      _config!.contextLength,
      temperature:      0.3,
      topP:             0.9,
      frequencyPenalty: 0.0,
      presencePenalty:  0.0,
    );

    final completer = Completer<String?>();
    String result   = '';
    try {
      fllamaChat(request, (response, _, done) {
        result = response.trim().replaceAll('"', '').replaceAll("'", '');
        if (done && !completer.isCompleted) completer.complete(result);
      });
      return await completer.future
          .timeout(const Duration(seconds: 15), onTimeout: () => null);
    } catch (_) {
      if (!completer.isCompleted) completer.complete(null);
      return null;
    }
  }

  // ── Lifecycle ──────────────────────────────────────────────────────────────

  void cancel() {
    _cancelled = true;
    _status    = LlmServiceStatus.ready;
  }

  void dispose() => cancel();
}
