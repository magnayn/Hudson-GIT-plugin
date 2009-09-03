package hudson.plugins.git.submodule;

import hudson.plugins.git.GitException;

import java.io.IOException;

public interface ICombinator
{
   /**
    * Create, in the local repository, any new submodule combinations that are required.
    * 
    * @param rootToExamine
    * @throws GitException
    * @throws IOException
    */
   void createSubmoduleCombinations() throws GitException, IOException;
}
