/*
 * ModeShape (http://www.modeshape.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.modeshape.web.client.admin;

import com.smartgwt.client.widgets.layout.VLayout;
import org.modeshape.web.client.Console;
import org.modeshape.web.client.JcrServiceAsync;

/**
 * @author kulikov
 */
public class AdminView extends VLayout {

    private final Console console;

    public AdminView( Console console) {
        this.console = console;

        addMember(new BackupControl(this));
        addMember(new RestoreControl(this));
        addMember(new BackupDownloadControl(this));
        addMember(new UploadRestoreControl(this));
        addMember(new Metrics(this));
    }

    protected Console console() {
        return console;
    }
    
    protected JcrServiceAsync jcrService() {
        return console.jcrService();
    }
    
    protected String repository() {
        return console.contents().repository();
    }
    
}

