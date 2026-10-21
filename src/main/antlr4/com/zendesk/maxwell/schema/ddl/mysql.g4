grammar mysql;

import mysql_literal_tokens,
       mysql_idents,
       mysql_alter_table,
       mysql_alter_database,
       mysql_create_database,
       mysql_create_table,
       mysql_drop,
       mysql_rename,
       mysql_view;

@lexer::members {
	private int serverVersionId = ExecutableComment.ALL_VERSIONS;

	public void setServerVersionId(int serverVersionId) {
		this.serverVersionId = serverVersionId;
	}

	/* MySQL ignores the whole body of a version-gated comment that is newer than the server. */
	private void skipInactiveExecutableComment() {
		if ( ExecutableComment.isActive(getText(), serverVersionId) )
			return;

		while ( _input.LA(1) != CharStream.EOF ) {
			boolean closing = _input.LA(1) == '*' && _input.LA(2) == '/';
			getInterpreter().consume(_input);
			if ( closing ) {
				getInterpreter().consume(_input);
				return;
			}
		}
	}
}

parse: statement?
       EOF;

statement:
    alter_table
  | alter_view
  | alter_database
  | create_database
  | create_table
  | create_view
  | drop_database
  | drop_table
  | drop_view
  | rename_table
  | BEGIN
  ;
