import isString from "lodash/isString";
import React from "react";
import { FormattedMessage } from "react-intl";

import { Button } from "components/ui/Button";
import { Modal } from "components/ui/Modal";

import styles from "./ConfirmationModal.module.scss";
import useLoadingState from "../../../hooks/useLoadingState";

export interface ConfirmationModalProps {
  onClose: () => void;
  title: string;
  text: string | React.ReactNode;
  textValues?: Record<string, string | number>;
  submitButtonText: string;
  onSubmit: () => void;
  submitButtonDataId?: string;
  cancelButtonText?: string;
  additionalContent?: React.ReactNode;
  submitButtonVariant?: "danger" | "primary";
}

export const ConfirmationModal: React.FC<ConfirmationModalProps> = ({
  onClose,
  title,
  text,
  additionalContent,
  textValues,
  onSubmit,
  submitButtonText,
  submitButtonDataId,
  cancelButtonText,
  submitButtonVariant = "danger",
}) => {
  const { isLoading, startAction } = useLoadingState();
  const onSubmitBtnClick = () => startAction({ action: async () => onSubmit() });

  return (
    <Modal onClose={onClose} title={<FormattedMessage id={title} />} testId="confirmationModal">
      <div className={styles.content}>
        {isString(text) ? <FormattedMessage id={text} values={textValues} /> : text}
        {additionalContent}
        <div className={styles.buttonContent}>
          <Button
            className={styles.buttonWithMargin}
            onClick={onClose}
            type="button"
            variant="secondary"
            disabled={isLoading}
          >
            <FormattedMessage id={cancelButtonText ?? "form.cancel"} />
          </Button>
          <Button
            variant={submitButtonVariant}
            onClick={onSubmitBtnClick}
            data-id={submitButtonDataId}
            isLoading={isLoading}
          >
            <FormattedMessage id={submitButtonText} />
          </Button>
        </div>
      </div>
    </Modal>
  );
};
