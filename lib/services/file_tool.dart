import 'dart:convert';
import 'dart:io';

import 'package:fllama/fllama.dart';
import 'package:path_provider/path_provider.dart';

/// The single tool exposed to the model.
final createFileTool = Tool(
  name: 'create_file',
  description:
      'Creates a text file on the device with the given name and content. '
      'Call this whenever the user asks to save, create, or write a file '
      '(code, notes, scripts, config, etc.).',
  jsonSchema: jsonEncode({
    'type': 'object',
    'properties': {
      'filename': {
        'type': 'string',
        'description':
            'File name including extension, e.g. "hello.py" or "notes.txt". '
            'Do NOT include a directory path.',
      },
      'content': {
        'type': 'string',
        'description': 'Full text content to write to the file.',
      },
    },
    'required': ['filename', 'content'],
  }),
);

/// Returns the directory where user-created files are saved.
/// Path: <externalStorage>/Documents/
/// Visible in any Android file manager.
Future<String> filesDirectory() async {
  final base = await getExternalStorageDirectory();
  final root = base?.path ?? (await getApplicationDocumentsDirectory()).path;
  final dir  = Directory('$root/Documents');
  if (!await dir.exists()) await dir.create(recursive: true);
  return dir.path;
}

/// Executes the create_file tool call.
/// Returns a human-readable result string to feed back to the model.
Future<String> executeCreateFile(Map<String, dynamic> args) async {
  final filename = args['filename'] as String? ?? 'output.txt';
  final content  = args['content']  as String? ?? '';

  // Sanitise: strip any directory separators from the filename.
  final safeName = filename.split(RegExp(r'[/\\]')).last;

  try {
    final dir  = await filesDirectory();
    final file = File('$dir/$safeName');
    await file.writeAsString(content);
    return 'File created successfully at: ${file.path}';
  } catch (e) {
    return 'Error creating file "$safeName": $e';
  }
}
