package com.gitblit.transport.ssh;

import java.io.IOException;

import org.apache.sshd.common.Session;
import org.apache.sshd.common.file.FileSystemFactory;
import org.apache.sshd.common.file.FileSystemView;
import org.apache.sshd.common.file.SshFile;

public class DisabledFilesystemFactory implements FileSystemFactory {

	@Override
	public FileSystemView createFileSystemView(Session session) throws IOException {
		return new FileSystemView() {
			@Override
			public SshFile getFile(SshFile baseDir, String file) {
				return null;
			}

			@Override
			public SshFile getFile(String file) {
				return null;
			}
		};
	}
}
