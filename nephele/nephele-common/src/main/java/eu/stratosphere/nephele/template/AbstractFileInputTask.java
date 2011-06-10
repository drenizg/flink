/***********************************************************************************************************************
 *
 * Copyright (C) 2010 by the Stratosphere project (http://stratosphere.eu)
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 **********************************************************************************************************************/

package eu.stratosphere.nephele.template;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import eu.stratosphere.nephele.fs.BlockLocation;
import eu.stratosphere.nephele.fs.FileInputSplit;
import eu.stratosphere.nephele.fs.FileStatus;
import eu.stratosphere.nephele.fs.FileSystem;
import eu.stratosphere.nephele.util.StringUtils;

/**
 * Specialized subtype of {@link AbstractInputTask} for tasks which are supposed to generate input from
 * a file. In addition to {@link AbstractInputTask} this class includes a method to query file splits
 * which should be read during the task's execution.
 * 
 * @author warneke
 */
public abstract class AbstractFileInputTask extends AbstractInputTask
{
	/**
	 * The fraction that the last split may be larger than the others.
	 */
	private static final float MAX_SPLIT_SIZE_DISCREPANCY = 1.1f;
	
	// --------------------------------------------------------------------------------------------
	
	/**
	 * Returns an iterator to a (possible empty) list of file input splits which is expected to be consumed by this
	 * instance of the {@link AbstractFileInputTask}.
	 * 
	 * @return an iterator to a (possible empty) list of file input splits.
	 */
	public Iterator<FileInputSplit> getFileInputSplits()
	{
		final InputSplit[] inputSplits = getTaskInputSplits();
		final List<FileInputSplit> fileInputSplits = new ArrayList<FileInputSplit>();

		for (int i = 0; i < inputSplits.length; i++) {
			fileInputSplits.add((FileInputSplit) inputSplits[i]);
		}

		return fileInputSplits.iterator();
	}
	
	@Override
	public FileInputSplit[] computeInputSplits()
	{
		if (this.path == null) {
			throw new IllegalConfigurationException("Cannot generate input splits, path is not set");
		}

		final int numSubtasks = getNumberOfSubtasks();
		final List<FileInputSplit> inputSplits = new ArrayList<FileInputSplit>();

		// get all the files that are involved in the splits
		List<FileStatus> files = new ArrayList<FileStatus>();
		long totalLength = 0;

		try {
			final FileSystem fs = this.path.getFileSystem();
			final FileStatus pathFile = fs.getFileStatus(this.path);

			if (pathFile.isDir()) {
				// input is directory. list all contained files
				final FileStatus[] dir = fs.listStatus(this.path);
				for (int i = 0; i < dir.length; i++) {
					if (!dir[i].isDir()) {
						files.add(dir[i]);
						totalLength += dir[i].getLen();
					}
				}

			} else {
				files.add(pathFile);
				totalLength += pathFile.getLen();
			}

			final long minSplitSize = 1;
			final long maxSplitSize = (numSubtasks < 1) ? Long.MAX_VALUE : (totalLength / numSubtasks + 
						(totalLength % numSubtasks == 0 ? 0 : 1));

			// now that we have the files, generate the splits
			for (final FileStatus file : files) {

				final long len = file.getLen();
				final long blockSize = file.getBlockSize();

				final long splitSize = Math.max(minSplitSize, Math.min(maxSplitSize, blockSize));
				final long halfSplit = splitSize >>> 1;

				final long maxBytesForLastSplit = (long) (splitSize * MAX_SPLIT_SIZE_DISCREPANCY);

				if (len > 0) {

					// get the block locations and make sure they are in order with respect to their offset
					final BlockLocation[] blocks = fs.getFileBlockLocations(file, 0, len);
					Arrays.sort(blocks);

					long bytesUnassigned = len;
					long position = 0;

					int blockIndex = 0;

					while (bytesUnassigned > maxBytesForLastSplit) {
						// get the block containing the majority of the data
						blockIndex = getBlockIndexForPosition(blocks, position, halfSplit, blockIndex);
						// create a new split
						FileInputSplit fis = new FileInputSplit(file.getPath(), position, splitSize, blocks[blockIndex]
							.getHosts());
						inputSplits.add(fis);

						// adjust the positions
						position += splitSize;
						bytesUnassigned -= splitSize;
					}

					// assign the last split
					if (bytesUnassigned > 0) {
						blockIndex = getBlockIndexForPosition(blocks, position, halfSplit, blockIndex);
						final FileInputSplit fis = new FileInputSplit(file.getPath(), position, bytesUnassigned,
							blocks[blockIndex].getHosts());
						inputSplits.add(fis);
					}
				} else {
					// special case with a file of zero bytes size
					final BlockLocation[] blocks = fs.getFileBlockLocations(file, 0, 0);
					String[] hosts;
					if (blocks.length > 0) {
						hosts = blocks[0].getHosts();
					} else {
						hosts = new String[0];
					}
					final FileInputSplit fis = new FileInputSplit(file.getPath(), 0, 0, hosts);
					inputSplits.add(fis);
				}
			}

		}
		catch (IOException ioe) {
			throw new IllegalConfigurationException("Cannot generate input splits from path '" + this.path.toString()
				+ "': " + StringUtils.stringifyException(ioe));
		}

		return inputSplits.toArray(new FileInputSplit[inputSplits.size()]);
	}
	
	/**
	 * Retrieves the index of the <tt>BlockLocation</tt> that contains the part of the file described by the given
	 * offset.
	 * 
	 * @param blocks
	 *        The different blocks of the file. Must be ordered by their offset.
	 * @param offset
	 *        The offset of the position in the file.
	 * @param startIndex
	 *        The earliest index to look at.
	 * @return The index of the block containing the given position.
	 */
	private final int getBlockIndexForPosition(BlockLocation[] blocks, long offset, long halfSplitSize, int startIndex) {
		// go over all indexes after the startIndex
		for (int i = startIndex; i < blocks.length; i++) {
			long blockStart = blocks[i].getOffset();
			long blockEnd = blockStart + blocks[i].getLength();

			if (offset >= blockStart && offset < blockEnd) {
				// got the block where the split starts
				// check if the next block contains more than this one does
				if (i < blocks.length - 1 && blockEnd - offset < halfSplitSize) {
					return i + 1;
				} else {
					return i;
				}
			}
		}
		throw new IllegalArgumentException("The given offset is not contained in the any block.");
	}
}
