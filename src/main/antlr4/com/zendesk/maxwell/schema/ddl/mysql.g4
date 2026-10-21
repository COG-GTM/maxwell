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
	private Integer serverVersionId;

	public void setServerVersionId(Integer serverVersionId) {
		this.serverVersionId = serverVersionId;
	}

	/* positioned just after "/*!": true when MySQL would ignore the comment body
	   because its version number is above the source server's version. */
	private boolean isInactiveVersionComment() {
		if ( serverVersionId == null )
			return false;

		StringBuilder digits = new StringBuilder();
		for ( int i = 1; Character.isDigit(_input.LA(i)); i++ )
			digits.appendCodePoint(_input.LA(i));

		return com.zendesk.maxwell.schema.ddl.VersionComments.isInactive(digits.toString(), serverVersionId);
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
