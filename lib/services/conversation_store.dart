import 'dart:convert';
import 'dart:io';

import 'package:path_provider/path_provider.dart';

import '../models/conversation.dart';

/// Persists conversations as individual JSON files under
/// <appDocuments>/conversations/<id>.json
class ConversationStore {
  static Future<Directory> _dir() async {
    final base = await getApplicationDocumentsDirectory();
    final dir  = Directory('${base.path}/conversations');
    if (!await dir.exists()) await dir.create(recursive: true);
    return dir;
  }

  static Future<File> _file(String id) async {
    final dir = await _dir();
    return File('${dir.path}/$id.json');
  }

  /// Save (create or overwrite) a conversation.
  static Future<void> save(Conversation conv) async {
    conv.updatedAt = DateTime.now();
    final file = await _file(conv.id);
    await file.writeAsString(jsonEncode(conv.toJson()));
  }

  /// Load all conversations, sorted newest first.
  static Future<List<Conversation>> loadAll() async {
    final dir   = await _dir();
    final files = dir.listSync().whereType<File>().toList();
    final convs = <Conversation>[];
    for (final f in files) {
      try {
        final text = await f.readAsString();
        convs.add(Conversation.fromJson(jsonDecode(text) as Map<String, dynamic>));
      } catch (_) {
        // Skip corrupt files.
      }
    }
    convs.sort((a, b) => b.updatedAt.compareTo(a.updatedAt));
    return convs;
  }

  /// Delete a single conversation by id.
  static Future<void> delete(String id) async {
    final file = await _file(id);
    if (await file.exists()) await file.delete();
  }
}
