import {Component, OnInit, ViewContainerRef} from '@angular/core';
import { QuerySenderService } from '../services/query-sender.service';
import {MdSnackBar, MdDialogRef, MdDialog} from '@angular/material';
import {SearchResultDialog} from "./search-result.component";

@Component({
  selector: 'app-search-bar',
  templateUrl: './search-bar.component.html'
})
export class SearchBarComponent implements OnInit {

  private query: string;

  dialogRef: MdDialogRef<SearchResultDialog>;

  constructor(private querySender: QuerySenderService,
              private mdSnackBar: MdSnackBar,
              public dialog: MdDialog) {
  }

  ngOnInit() {
  }

  /**
   * Open Dialog displaying NLP interpretation of the NLP query from search-bar component
   * @param response result of the NLP interpretation
   */
  openDialog(queryResponse) {
    this.dialogRef = this.dialog.open(SearchResultDialog);

    // Passing NLP interpretation as dialog parameter
    this.dialogRef.componentInstance.response = queryResponse;

    this.dialogRef.afterClosed().subscribe(dialogResult => {
      // When 'Start request' button clicked
      if (dialogResult) {
        // Send request to start processing query of queryResponse.id
        this.querySender.sendStart(queryResponse.id).subscribe(
          response => console.log('[INFO] Query started: ',queryResponse.id),
          error => this.errorHandler(error)
        );
      }
      this.dialogRef = null;
    });
  }

  sendRequest() {
    console.log("[INFO] Query sent: " + this.query);
    this.querySender.send(this.query).subscribe(
      response => this.openDialog(response),
      error => this.errorHandler(error)
    );
  }

  errorHandler(error: any) {
    let logStr = '[ERROR] [QUERY SENDER SERVICE] ' + error;
    console.log(logStr);
    this.mdSnackBar.open(logStr, 'Close');
  }

}