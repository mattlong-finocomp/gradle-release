/*
 * This file is part of the gradle-release plugin.
 *
 * (c) Eric Berry
 * (c) ResearchGate GmbH
 *
 * For the full copyright and license information, please view the LICENSE
 * file that was distributed with this source code.
 */

package net.researchgate.release

import org.gradle.api.Project

import java.util.regex.Matcher
import org.gradle.api.GradleException

class SvnAdapter extends BaseScmAdapter {

	private static final String ERROR = 'Commit failed'

	private static final def urlPattern = ~/URL:\s(.*?)(\/(trunk|branches|tags).*?)$/

	private static final def revPattern = ~/Revision:\s(.*?)$/

	private static final def commitPattern = ~/Committed revision\s(.*?)\.$/

	private static final def environment = [LANG: 'C', LC_MESSAGES: 'C', LC_ALL: ''];

	SvnAdapter(Project project) {
        super(project)
    }

	class SvnConfig {
		String username
		String password
	}

	@Override
	Object createNewConfig() {
		return new SvnConfig();
	}

	@Override
	boolean isSupported(File directory) {
		if (!directory.list().grep('.svn')) {
			return directory.parentFile? isSupported(directory.parentFile) : false
		}

		true
	}

    void init() {
		String username = findProperty('release.svn.username')
		if (username) {
            extension.svn.username = username
		}

		String password = findProperty('release.svn.password')
		if (password) {
            extension.svn.password = password
		}

		findSvnUrl()
		project.ext.set('releaseSvnRev', null)
	}

	@Override
	void checkCommitNeeded() {
		String out = svnExec(['status'])
		def changes = []
		def unknown = []
		out.eachLine { line ->
			switch (line?.trim()?.charAt(0)) {
				case '?':
					unknown << line
					break
				default:
					changes << line
					break
			}
		}
		if (changes) {
			warnOrThrow(extension.failOnCommitNeeded, "You have ${changes.size()} un-commited changes.")
		}
		if (unknown) {
			warnOrThrow(extension.failOnUnversionedFiles, "You have ${unknown.size()} un-versioned files.")
		}
	}

	@Override
	void checkUpdateNeeded() {
		def props = project.properties
		String svnUrl = props.releaseSvnUrl
		String svnRev = props.initialSvnRev
		String svnRemoteRev = ''

		String out = svnExec(['status', '-q', '-u'])
		def missing = []
		out.eachLine { line ->
			switch (line?.trim()?.charAt(0)) {
				case '*':
					missing << line
					break
			}
		}
		if (missing) {
			warnOrThrow(extension.failOnUpdateNeeded, "You are missing ${missing.size()} changes.")
		}

		out = svnExec(['info', svnUrl])
		out.eachLine { line ->
			Matcher matcher = line =~ revPattern
			if (matcher.matches()) {
				svnRemoteRev = matcher.group(1)
				project.ext.set('releaseRemoteSvnRev', svnRemoteRev)
			}
		}
		if (svnRev != svnRemoteRev) {
			// warn that there's a difference in local revision versus remote
			warnOrThrow(extension.failOnUpdateNeeded, "Local revision (${svnRev}) does not match remote (${svnRemoteRev}), local revision is used in tag creation.")
		}
	}

	@Override
	void createReleaseTag(String message) {
		def props = project.properties
		String svnUrl = props.releaseSvnUrl
		String svnRev = props.releaseSvnRev ?: props.initialSvnRev //release set by commit below when needed, no commit => initial
		String svnRoot = props.releaseSvnRoot
		String svnTag = tagName()

		svnExec(['cp', "${svnUrl}@${svnRev}", "${svnRoot}/tags/${svnTag}", '-m', message])
	}

	@Override
	void commit(String message) {
		String out = svnExec(['ci', '-m', message], errorMessage: 'Error committing new version', errorPatterns: [ERROR])

		// After the first commit we need to find the new revision so the tag is made from the correct revision
		if (project.properties.releaseSvnRev == null) {
			out.eachLine { line ->
				Matcher matcher = line =~ commitPattern
				if (matcher.matches()) {
					String revision = matcher.group(1)
					project.ext.set('releaseSvnRev', revision)
				}
			}
		}
	}

	@Override
	void revert() {
		svnExec(['revert', findPropertiesFile().name], errorMessage: 'Error reverting changes made by the release plugin.', errorPatterns: [ERROR])
	}

    /**
     * Adds the executable and optional also username/password
     *
     * @param options
     * @param commands
     * @return
     */
	private String svnExec(
        Map options = [:],
		List<String> commands
	) {
		if (extension.svn.username) {
			if (extension.svn.password) {
                commands.addAll(0, ['--password', extension.svn.password]);
			}
            commands.addAll(0, ['--non-interactive', '--no-auth-cache', '--username', extension.svn.username]);
		}
        commands.add(0, 'svn');

        options['env'] = environment;

		exec(options, commands)
	}

	private void findSvnUrl() {
		String out = svnExec(['info'])

		out.eachLine { line ->
			Matcher matcher = line =~ urlPattern
			if (matcher.matches()) {
				String svnRoot = matcher.group(1)
				String svnProject = matcher.group(2)
				project.ext.set('releaseSvnRoot', svnRoot)
				project.ext.set('releaseSvnUrl', "$svnRoot$svnProject")
			}
			matcher = line =~ revPattern
			if (matcher.matches()) {
				String revision = matcher.group(1)
				project.ext.set('initialSvnRev', revision)
			}
		}
		if (!project.hasProperty('releaseSvnUrl') || !project.hasProperty('initialSvnRev')) {
			throw new GradleException('Could not determine root SVN url or revision.')
		}
	}
}